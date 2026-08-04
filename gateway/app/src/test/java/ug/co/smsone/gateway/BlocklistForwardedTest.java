package ug.co.smsone.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The blocklist behind ONE declared ingress hop: the rightmost X-Forwarded-For entry (the one our
 * ingress appended) is the judged address — a blocked client is refused even though the socket peer
 * is the ingress, and a forged entry LEFT of the vouched one changes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.security.blocklist.cidrs[0]=9.0.0.0/8",
        "gateway.security.blocklist.trusted-proxy-hops=1"
})
class BlocklistForwardedTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> response.status(200).sendString(Mono.just("ok")))
            .bindNow();

    @Value("${local.server.port}")
    private int gatewayPort;

    @DynamicPropertySource
    static void backendUri(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + BACKEND.port());
    }

    @AfterAll
    static void stop() {
        BACKEND.disposeNow();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + gatewayPort).build();
    }

    @Test
    void theVouchedEntryIsJudged() {
        // Ingress vouched for 9.9.9.9 → blocked, though the socket peer is loopback.
        client().get().uri("/path-route/x")
                .header("X-Forwarded-For", "9.9.9.9")
                .exchange().expectStatus().isForbidden();

        // The vouched (rightmost) entry is clean; the forged left one is not believed.
        client().get().uri("/path-route/x")
                .header("X-Forwarded-For", "9.9.9.9, 8.8.8.8")
                .exchange().expectStatus().isOk();

        // No header: did not traverse the declared ingress — the socket peer decides (unblocked).
        client().get().uri("/path-route/x").exchange().expectStatus().isOk();
    }
}
