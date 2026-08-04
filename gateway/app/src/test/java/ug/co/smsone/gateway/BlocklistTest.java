package ug.co.smsone.gateway;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The front-door deny-list end to end on the real wire: a YAML-seeded CIDR covering the test
 * client's loopback refuses every routed request with 403 before auth or routing; the
 * {@code gatewayblocklist} endpoint lifts and re-imposes it at runtime (bare IPs normalize to host
 * routes); junk is refused loudly; and with zero declared proxy hops a client-supplied
 * X-Forwarded-For buys nothing — the socket peer decides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.security.blocklist.cidrs[0]=127.0.0.0/8"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlocklistTest {

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
    @Order(1)
    void aConfiguredCidrRefusesTheRequestBeforeAnythingElse() {
        client().get().uri("/path-route/x").exchange().expectStatus().isForbidden();

        client().get().uri("/actuator/gatewayblocklist").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(1)
                .jsonPath("$.entries[0].cidr").isEqualTo("127.0.0.0/8")
                .jsonPath("$.entries[0].source").isEqualTo("config");
    }

    @Test
    @Order(2)
    void runtimeUnblockAdmitsAndABareIpReblocksAsAHostRoute() {
        client().post().uri("/actuator/gatewayblocklist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cidr", "127.0.0.0/8", "blocked", false))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.removed").isEqualTo(true);

        client().get().uri("/path-route/x").exchange().expectStatus().isOk();

        // Ban the bare loopback IP — normalizes to /32 and bites immediately, flagged runtime.
        client().post().uri("/actuator/gatewayblocklist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cidr", "127.0.0.1", "blocked", true))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.cidr").isEqualTo("127.0.0.1/32")
                .jsonPath("$.source").isEqualTo("runtime");

        client().get().uri("/path-route/x").exchange().expectStatus().isForbidden();

        client().post().uri("/actuator/gatewayblocklist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cidr", "127.0.0.1/32", "blocked", false))
                .exchange().expectStatus().isOk();
        client().get().uri("/path-route/x").exchange().expectStatus().isOk();
    }

    @Test
    @Order(3)
    void junkIsRefusedLoudlyAndSpoofedForwardedForBuysNothing() {
        client().post().uri("/actuator/gatewayblocklist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cidr", "not-an-ip", "blocked", true))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.error").exists();

        // Block 9.0.0.0/8, then claim to BE 9.9.9.9 via XFF: with zero declared hops the header is
        // ignored and the loopback socket peer (currently unblocked) decides — the request passes.
        client().post().uri("/actuator/gatewayblocklist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cidr", "9.0.0.0/8", "blocked", true))
                .exchange().expectStatus().isOk();
        client().get().uri("/path-route/x")
                .header("X-Forwarded-For", "9.9.9.9")
                .exchange().expectStatus().isOk();
    }
}
