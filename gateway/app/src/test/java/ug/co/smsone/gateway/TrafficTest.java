package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

/**
 * Traffic management: the edge protects the backend. A rate-limited route trips 429 once a caller
 * outruns its token bucket (a real Valkey container); a slow backend fails fast at 504 instead of
 * hanging; an oversized body is rejected at 413 before it is proxied; ordinary traffic passes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrafficTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);
    private static final DisposableServer BACKEND;

    static {
        VALKEY.start();
        BACKEND = HttpServer.create().port(0)
                .route(routes -> routes
                        .route(request -> request.uri().startsWith("/slow"), (request, response) ->
                                response.sendString(Mono.just("slow").delayElement(Duration.ofSeconds(2))))
                        .route(request -> true, (request, response) ->
                                response.status(200).sendString(Mono.just("backend:" + request.uri()))))
                .bindNow();
    }

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + BACKEND.port());
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        BACKEND.disposeNow();
        VALKEY.stop();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void rateLimitedRouteTrips429OnABurst() {
        int limited = 0;
        int allowed = 0;
        boolean sawRetryAfter = false;
        for (int i = 0; i < 12; i++) {
            var result = client().get().uri("/rl/x").exchange().returnResult(String.class);
            int status = result.getStatus().value();
            if (status == 429) {
                limited++;
                // The edge 429 tells the caller how long to back off, like the plan-quota 429 does.
                if (result.getResponseHeaders().getFirst("Retry-After") != null) {
                    sawRetryAfter = true;
                }
            } else if (status == 200) {
                allowed++;
            }
        }
        assertThat(allowed).as("the burst capacity lets a few through").isGreaterThanOrEqualTo(1);
        assertThat(limited).as("the rest are rate limited").isGreaterThanOrEqualTo(1);
        assertThat(sawRetryAfter).as("an edge 429 carries Retry-After").isTrue();
    }

    @Test
    void edgeLimiterHeadersAreNamespacedSoTheyNeverCollideWithThePlanQuota() {
        // The edge burst limiter advertises its bucket under X-Edge-RateLimit-*, leaving the plain
        // X-RateLimit-* names for the modulith's per-tenant plan quota — so a caller never sees two
        // conflicting X-RateLimit-Remaining values through the gateway.
        client().get().uri("/rl/x").exchange()
                .expectHeader().exists("X-Edge-RateLimit-Remaining")
                .expectHeader().exists("X-Edge-RateLimit-Burst-Capacity")
                .expectHeader().doesNotExist("X-RateLimit-Remaining");
    }

    @Test
    void slowBackendTimesOutAt504() {
        client().get().uri("/slow/x").exchange()
                .expectStatus().isEqualTo(504)
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("GATEWAY_TIMEOUT");
    }

    @Test
    void oversizedBodyIsRejectedAt413() {
        // The request-size filter writes its own 413 (SCG-generated errors keep SCG's shape; only
        // routing/auth/timeout failures pass through the gateway envelope) — assert the status.
        client().post().uri("/size/x").bodyValue("x".repeat(500)).exchange()
                .expectStatus().isEqualTo(413);
    }

    @Test
    void ordinaryTrafficPasses() {
        client().get().uri("/path-route/ok").exchange().expectStatus().isOk();
    }
}
