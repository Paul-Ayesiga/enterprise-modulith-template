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
 * Distributed tracing: the edge propagates W3C trace context so one trace id spans gateway → backend.
 * The backend reflects the traceparent it received. With no inbound traceparent the gateway mints a
 * trace, echoes it as X-Trace-Id, and the backend sees that same trace id; with an inbound traceparent
 * the caller's trace id is adopted end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TracingTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> {
                String traceparent = request.requestHeaders().get("traceparent");
                return response.status(200)
                        .header("X-Backend-Traceparent", traceparent == null ? "none" : traceparent)
                        .sendString(Mono.just("ok"));
            })
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
    void mintsATraceAndTheBackendSeesTheSameId() {
        var result = client().get().uri("/path-route/x").exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Trace-Id")
                .returnResult(String.class);

        String traceId = result.getResponseHeaders().getFirst("X-Trace-Id");
        String backendTraceparent = result.getResponseHeaders().getFirst("X-Backend-Traceparent");
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(traceIdOf(backendTraceparent)).as("backend joined the gateway's trace").isEqualTo(traceId);
    }

    @Test
    void adoptsACallersTraceEndToEnd() {
        String callerTrace = "4bf92f3577b34da6a3ce929d0e0e4736";
        String inbound = "00-" + callerTrace + "-00f067aa0ba902b7-01";

        var result = client().get().uri("/path-route/x").header("traceparent", inbound).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Trace-Id", callerTrace)
                .returnResult(String.class);

        String backendTraceparent = result.getResponseHeaders().getFirst("X-Backend-Traceparent");
        assertThat(traceIdOf(backendTraceparent)).isEqualTo(callerTrace);
    }

    private static String traceIdOf(String traceparent) {
        return traceparent == null ? null : traceparent.split("-")[1];
    }
}
