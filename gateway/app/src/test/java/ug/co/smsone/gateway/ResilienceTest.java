package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Resilience: a route's circuit breaker returns 503 (via the fallback) while its backend is failing,
 * and recovers to 200 once the backend heals and the open window lapses; a retry route re-attempts a
 * transient 5xx on an idempotent GET until it succeeds. The backend is a reactor-netty stub whose
 * failure is switchable and whose attempts are counted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResilienceTest {

    private static final AtomicBoolean FAILING = new AtomicBoolean(false);
    private static final AtomicInteger RETRY_ATTEMPTS = new AtomicInteger(0);
    private static final DisposableServer BACKEND;

    static {
        BACKEND = HttpServer.create().port(0)
                .route(routes -> routes
                        .route(request -> request.uri().startsWith("/cb"), (request, response) ->
                                FAILING.get() ? response.status(500).send()
                                        : response.status(200).sendString(Mono.just("ok")))
                        .route(request -> request.uri().startsWith("/retry"), (request, response) ->
                                RETRY_ATTEMPTS.incrementAndGet() <= 2 ? response.status(500).send()
                                        : response.status(200).sendString(Mono.just("ok")))
                        .route(request -> true, (request, response) ->
                                response.status(200).sendString(Mono.just("ok"))))
                .bindNow();
    }

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
    void circuitBreakerReturns503WhileFailingAndRecovers() throws Exception {
        FAILING.set(true);
        for (int i = 0; i < 6; i++) {
            client().get().uri("/cb/x").exchange().expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.errors[0].code").isEqualTo("SERVICE_UNAVAILABLE");
        }
        FAILING.set(false);
        // The open window (1s) lapses, the circuit half-opens, the healed backend closes it → 200.
        assertThat(awaitStatus("/cb/x", 200)).isTrue();
    }

    @Test
    void retriesATransientFailureThenSucceeds() {
        RETRY_ATTEMPTS.set(0);
        client().get().uri("/retry/x").exchange().expectStatus().isOk();
        assertThat(RETRY_ATTEMPTS.get()).as("2 failures + 1 success").isGreaterThanOrEqualTo(3);
    }

    private boolean awaitStatus(String uri, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            int status = client().get().uri(uri).exchange().returnResult(String.class).getStatus().value();
            if (status == expected) {
                return true;
            }
            Thread.sleep(150);
        }
        return false;
    }
}
