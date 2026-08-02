package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import ug.co.smsone.gateway.core.audit.AuditSink;
import ug.co.smsone.gateway.core.audit.EdgeAuditEvent;

/**
 * The edge audit seam (gateway side): a denial publishes an {@link EdgeAuditEvent} to the {@link
 * AuditSink} port. A capturing sink stands in for the platform adapter, so this proves the filter emits
 * the right event (action / reason / path / status) without a running modulith.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EdgeAuditTest.CapturingSinkConfig.class)
class EdgeAuditTest {

    @TestConfiguration
    static class CapturingSinkConfig {
        @Bean
        AuditSink capturingAuditSink() {
            return event -> {
                CAPTURED.add(event);
                return Mono.empty();
            };
        }
    }

    private static final List<EdgeAuditEvent> CAPTURED = new CopyOnWriteArrayList<>();

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

    @BeforeEach
    void clear() {
        CAPTURED.clear();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void aDenialPublishesAnEdgeAuditEvent() {
        client().get().uri("/secured/x").exchange().expectStatus().isUnauthorized();

        EdgeAuditEvent event = awaitEvent();
        assertThat(event).isNotNull();
        assertThat(event.action()).isEqualTo("gateway.access_denied");
        assertThat(event.reason()).isEqualTo("unauthorized");
        assertThat(event.status()).isEqualTo(401);
        assertThat(event.path()).isEqualTo("/secured/x");
        assertThat(event.method()).isEqualTo("GET");
    }

    @Test
    void anAllowedRequestPublishesNothing() {
        client().get().uri("/open/x").exchange().expectStatus().isOk();
        // Give any stray async publish a moment; an open route must not audit.
        sleep(200);
        assertThat(CAPTURED).isEmpty();
    }

    /** The publish is fire-and-forget, so poll briefly for it. */
    private EdgeAuditEvent awaitEvent() {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (!CAPTURED.isEmpty()) {
                return CAPTURED.get(0);
            }
            sleep(20);
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
