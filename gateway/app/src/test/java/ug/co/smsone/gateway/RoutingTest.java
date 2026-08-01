package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The Phase 1 gate: config-driven routing to a real backend. Each predicate kind routes; an unknown
 * path is a clean {@code NO_ROUTE} 404 envelope; a request id is minted when absent, honored when
 * present, and propagated to the backend + echoed to the client. The backend is a reactor-netty stub
 * that echoes the request id it saw, so propagation is observable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutingTest {

    private static final DisposableServer BACKEND;

    static {
        BACKEND = HttpServer.create().port(0)
                .handle((request, response) -> {
                    String seen = request.requestHeaders().get("X-Request-Id");
                    return response.status(200)
                            .header("X-Backend-Saw-Request-Id", seen == null ? "none" : seen)
                            .sendString(Mono.just("backend:" + request.uri()));
                })
                .bindNow();
    }

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

    @DynamicPropertySource
    static void backendUri(DynamicPropertyRegistry registry) {
        // A scalar the route yaml resolves (${backend.uri}) — avoids clobbering the services[0] list element.
        registry.add("backend.uri", () -> "http://localhost:" + BACKEND.port());
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.disposeNow();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer().baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void routesByPath() {
        client().get().uri("/path-route/x").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("backend:/path-route/x"));
    }

    @Test
    void routesByHost() {
        client().get().uri("/anything").header("Host", "a.byhost.local").exchange()
                .expectStatus().isOk();
    }

    @Test
    void routesByHeader() {
        client().get().uri("/anything").header("X-Route", "header-match").exchange()
                .expectStatus().isOk();
    }

    @Test
    void routesByMethod() {
        client().method(HttpMethod.DELETE).uri("/anything").exchange()
                .expectStatus().isOk();
    }

    @Test
    void routesByQuery() {
        client().get().uri("/anything?flag=on").exchange()
                .expectStatus().isOk();
    }

    @Test
    void unknownPathIsNoRoute404() {
        client().get().uri("/no-such-route").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("NO_ROUTE");
    }

    @Test
    void mintsAndPropagatesRequestId() {
        client().get().uri("/path-route/x").exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().value("X-Backend-Saw-Request-Id", seen -> assertThat(seen).isNotEqualTo("none"));
    }

    @Test
    void honorsAClientSuppliedRequestId() {
        client().get().uri("/path-route/x").header("X-Request-Id", "client-123").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "client-123")
                .expectHeader().valueEquals("X-Backend-Saw-Request-Id", "client-123");
    }
}
