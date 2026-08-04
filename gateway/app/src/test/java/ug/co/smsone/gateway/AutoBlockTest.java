package ug.co.smsone.gateway;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Dynamic auto-blocking end to end on the real wire. Auth denials are counted and the threshold set
 * to 3: a source that earns three 401s on a secured route is auto-blocked, so its next request is a
 * blocklist 403 (refused at +3, before auth — even on an open route, because the block is on the
 * source not the route), the {@code gatewayblocklist} endpoint lists it with an {@code auto} source
 * and a TTL, and an operator can lift it. The allow-set safety valve is proved in
 * {@link AutoBlockAllowlistTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.security.blocklist.auto.enabled=true",
        "gateway.security.blocklist.auto.threshold=3",
        "gateway.security.blocklist.auto.window=PT5M",
        "gateway.security.blocklist.auto.block-duration=PT10M"
})
class AutoBlockTest {

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
    void threeAuthDenialsAutoBlockTheSource() {
        // Three unauthenticated hits on a secured route → three 401 strikes. The strike for a thrown
        // 401 is recorded before the response commits, so by the third the block is already live.
        for (int i = 0; i < 3; i++) {
            client().get().uri("/secured/x").exchange().expectStatus().isUnauthorized();
        }

        // The next request is refused by the blocklist at +3 — a 403, not the 401 auth would give.
        client().get().uri("/secured/x").exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        // Even an OPEN route is refused now — the block is on the source, not the route.
        client().get().uri("/open/x").exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);

        client().get().uri("/actuator/gatewayblocklist").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.entries[?(@.source == 'auto')]").isNotEmpty()
                .jsonPath("$.autoBlock.enabled").isEqualTo(true)
                .jsonPath("$.autoBlock.threshold").isEqualTo(3);

        // An operator lifts the false positive; traffic flows again.
        client().post().uri("/actuator/gatewayblocklist")
                .header("content-type", "application/json")
                .bodyValue("{\"cidr\":\"127.0.0.1\",\"blocked\":false}")
                .exchange().expectStatus().isOk();
        client().get().uri("/open/x").exchange().expectStatus().isOk();
    }
}
