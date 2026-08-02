package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Metrics: the gateway records Micrometer meters and exposes them at /actuator/prometheus. Driving a
 * routed request registers SCG's per-route request timer, and an unauthenticated call to a secured
 * route registers the edge's own auth-failure counter — both must appear in the scrape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsTest {

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
    void prometheusExposesGatewayRequestAndAuthFailureMetrics() {
        // A routed request → SCG per-route request timer; an unauthenticated secured call → auth-failure counter.
        client().get().uri("/path-route/x").exchange().expectStatus().isOk();
        client().get().uri("/secured/x").exchange().expectStatus().isUnauthorized();

        String scrape = client().get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody()
                .collectList().block().stream().reduce("", String::concat);

        assertThat(scrape).contains("spring_cloud_gateway_requests");
        assertThat(scrape).contains("gateway_auth_failures");
        assertThat(scrape).contains("reason=\"unauthorized\"");
    }
}
