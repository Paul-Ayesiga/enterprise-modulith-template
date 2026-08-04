package ug.co.smsone.gateway;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The allow-set is the dynamic layer's safety valve: an allowlisted source is never counted for
 * abuse and never auto-blocked, so a health checker or office range can't be locked out by its own
 * bad minute. Same abuse as {@link AutoBlockTest}, but loopback is on the allow-set — it stays 401
 * (auth), never 403 (blocked), and the deny-list stays empty.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.security.blocklist.auto.enabled=true",
        "gateway.security.blocklist.auto.threshold=3",
        "gateway.security.blocklist.allow=127.0.0.0/8"
})
class AutoBlockAllowlistTest {

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
    private int port;

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
                .baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anAllowlistedSourceIsNeverAutoBlocked() {
        for (int i = 0; i < 8; i++) {
            client().get().uri("/secured/x").exchange().expectStatus().isUnauthorized();
        }
        // Still 401 (auth), never 403 (blocked) — the allow-set exempted it from the abuse count.
        client().get().uri("/secured/x").exchange().expectStatus().isUnauthorized();
        client().get().uri("/open/x").exchange().expectStatus().isOk();

        client().get().uri("/actuator/gatewayblocklist").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.count").isEqualTo(0);
    }
}
