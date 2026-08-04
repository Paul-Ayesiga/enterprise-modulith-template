package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Durable (persistent) routes end to end. A route registered with {@code persist:true} serves
 * immediately AND its spec is written to the Valkey set + per-id key that the boot-time hydrate reads
 * — so it comes back after a restart. The routes read marks it {@code persistent:true}; deleting it
 * clears it from Valkey so it does NOT resurrect on the next boot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.persistent-routes.enabled=true"
})
class PersistentRouteTest {

    private static final String ID = "prtest-route";
    private static final String IDS_KEY = "gwroutes:persistent:ids";
    private static final String DEF_KEY = "gwroutes:persistent:def:" + ID;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);
    private static final DisposableServer BACKEND;

    static {
        VALKEY.start();
        BACKEND = HttpServer.create().port(0)
                .handle((request, response) -> response.status(200).sendString(Mono.just("ok")))
                .bindNow();
    }

    @Value("${local.server.port}")
    private int gatewayPort;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + BACKEND.port());
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        BACKEND.disposeNow();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(10))
                .baseUrl("http://localhost:" + gatewayPort).build();
    }

    @Test
    void aDurableRouteServesAndIsWrittenToValkey() {
        // No route for this path yet.
        client().get().uri("/prtest/x").exchange().expectStatus().isNotFound();

        // Register it durably.
        client().post().uri("/actuator/gatewayroutes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("id", ID, "path", "/prtest/**", "serviceId", "backend",
                        "order", 5, "persist", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("registered")
                .jsonPath("$.persistent").isEqualTo(true);

        // Serves immediately (runtime tier).
        client().get().uri("/prtest/x").exchange().expectStatus().isOk();

        // Marked persistent in the route table.
        client().get().uri("/actuator/gatewayroutes").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.id == '" + ID + "')].persistent").isEqualTo(true);

        // Durability proof: the spec is in the Valkey set + key that boot-time hydrate reads.
        assertThat(memberEventuallyPresent()).isTrue();
        assertThat(redis.opsForValue().get(DEF_KEY).block(Duration.ofSeconds(2)))
                .contains("/prtest/**");

        // Deleting it removes it from Valkey too — it won't come back on restart.
        client().delete().uri("/actuator/gatewayroutes/" + ID).exchange().expectStatus().isNoContent();
        client().get().uri("/prtest/x").exchange().expectStatus().isNotFound();
        assertThat(Boolean.TRUE.equals(redis.opsForSet().isMember(IDS_KEY, ID).block(Duration.ofSeconds(2)))).isFalse();
    }

    private boolean memberEventuallyPresent() {
        for (int i = 0; i < 40; i++) {
            if (Boolean.TRUE.equals(redis.opsForSet().isMember(IDS_KEY, ID).block(Duration.ofSeconds(1)))) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
