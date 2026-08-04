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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Durable (persistent) manual blocking end to end. A block added with {@code persist:true} refuses
 * the source immediately (like a runtime block) AND is written to the Valkey set that survives a
 * gateway restart — the endpoint reports it with source {@code persistent}, and the Valkey membership
 * is the durability proof. Unblocking lifts it from both the live snapshot and Valkey.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.security.blocklist.persistent.enabled=true"
})
class PersistentBlockTest {

    private static final String SKEY = "gwblock:persistent";

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
    void aPersistentBlockRefusesAndIsWrittenToValkey() {
        // Loopback (the test client) starts un-blocked.
        client().get().uri("/path-route/x").exchange().expectStatus().isOk();

        // Block it durably.
        client().post().uri("/actuator/gatewayblocklist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cidr", "127.0.0.1", "blocked", true, "persist", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.cidr").isEqualTo("127.0.0.1/32")
                .jsonPath("$.source").isEqualTo("persistent");

        // Refused now — before auth, like any block.
        client().get().uri("/path-route/x").exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);

        // Listed as a persistent entry.
        client().get().uri("/actuator/gatewayblocklist").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.persistentEnabled").isEqualTo(true)
                .jsonPath("$.entries[?(@.source == 'persistent')].cidr").isEqualTo("127.0.0.1/32");

        // Durability proof: it is in the Valkey set that survives a restart.
        assertThat(memberEventuallyPresent("127.0.0.1/32")).isTrue();

        // Unblock lifts it from both the snapshot and Valkey.
        client().post().uri("/actuator/gatewayblocklist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cidr", "127.0.0.1/32", "blocked", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.removed").isEqualTo(true);

        client().get().uri("/path-route/x").exchange().expectStatus().isOk();
        assertThat(Boolean.TRUE.equals(redis.opsForSet().isMember(SKEY, "127.0.0.1/32").block())).isFalse();
    }

    /** The Valkey write is fire-and-forget; poll briefly for it to land. */
    private boolean memberEventuallyPresent(String cidr) {
        for (int i = 0; i < 40; i++) {
            if (Boolean.TRUE.equals(redis.opsForSet().isMember(SKEY, cidr).block(Duration.ofSeconds(1)))) {
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
