package ug.co.smsone.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

/**
 * API lifecycle + versioning. A retired route no longer proxies — 410 Gone. A deprecated route still
 * routes but warns with Deprecation + Sunset headers. Versioning by header selects the route, and each
 * version carries its own lifecycle: v1 is deprecated (warns), v2 is current (does not).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LifecycleTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> response.status(200).sendString(Mono.just("ok")))
            .bindNow();

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

    @DynamicPropertySource
    static void backendUri(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + BACKEND.port());
    }

    @AfterAll
    static void stop() {
        BACKEND.disposeNow();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void retiredRouteIsGone() {
        client().get().uri("/gone/x").exchange()
                .expectStatus().isEqualTo(410)
                // The 410 explains itself (not a bare "GONE") and carries the Sunset date it went on.
                .expectHeader().valueEquals("Sunset", "Sun, 30 Jun 2024 23:59:59 GMT")
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo("GONE")
                .jsonPath("$.errors[0].detail").value(org.hamcrest.Matchers.containsString("retired"));
    }

    @Test
    void deprecatedRouteWarnsButStillRoutes() {
        client().get().uri("/dep/x").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Deprecation", "true")
                .expectHeader().valueEquals("Sunset", "Wed, 31 Dec 2025 23:59:59 GMT");
    }

    @Test
    void versioningSelectsTheRouteAndEachVersionCarriesItsLifecycle() {
        // v1 is routed by header and is deprecated
        client().get().uri("/versioned/x").header("X-API-Version", "v1").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Deprecation", "true");
        // v2 is the current version — routed, no deprecation warning
        client().get().uri("/versioned/x").header("X-API-Version", "v2").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Deprecation");
    }
}
