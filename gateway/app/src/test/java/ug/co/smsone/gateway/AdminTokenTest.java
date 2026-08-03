package ug.co.smsone.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

/**
 * The management-port credential: with {@code gateway.admin.token} set, the admin API demands
 * {@code Authorization: Bearer <token>} — 401 without, serves with — while {@code /actuator/health}
 * stays open for probes. (Unset, the filter is absent entirely; that default is every other admin
 * test in this suite.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "management.server.port=0",
        "gateway.admin.token=test-admin-secret"
})
class AdminTokenTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> response.status(200).sendString(Mono.just("ok")))
            .bindNow();

    @Value("${local.management.port}")
    private int adminPort;

    @DynamicPropertySource
    static void backendUri(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + BACKEND.port());
    }

    @AfterAll
    static void stop() {
        BACKEND.disposeNow();
    }

    private WebTestClient admin() {
        return WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                .baseUrl("http://localhost:" + adminPort).build();
    }

    @Test
    void adminApiDemandsTheTokenWhenConfigured() {
        admin().get().uri("/actuator/gatewayroutes").exchange()
                .expectStatus().isUnauthorized();
        admin().get().uri("/actuator/gatewayroutes")
                .header("X-Admin-Token", "wrong-secret").exchange()
                .expectStatus().isUnauthorized();
        admin().get().uri("/actuator/gatewayroutes")
                .header("X-Admin-Token", "test-admin-secret").exchange()
                .expectStatus().isOk();
    }

    @Test
    void healthStaysOpenForProbes() {
        // Health may be DOWN in this slice (no Valkey booted) — open means "not 401", not "UP".
        admin().get().uri("/actuator/health").exchange()
                .expectStatus().value(status ->
                        org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401));
    }

}
