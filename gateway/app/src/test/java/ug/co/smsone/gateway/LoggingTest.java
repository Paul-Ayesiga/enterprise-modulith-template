package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * Separable observability streams: authN/authZ decisions go to the {@code gateway.security} logger and
 * every request to {@code gateway.access}, on distinct loggers so an operator can route each to its own
 * appender. A ListAppender per stream proves a denial lands in security (and not in access), and a
 * routed request lands in access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoggingTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> response.status(200).sendString(Mono.just("ok")))
            .bindNow();

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

    private final Logger securityLogger = (Logger) LoggerFactory.getLogger("gateway.security");
    private final Logger accessLogger = (Logger) LoggerFactory.getLogger("gateway.access");
    private final ListAppender<ILoggingEvent> securityEvents = new ListAppender<>();
    private final ListAppender<ILoggingEvent> accessEvents = new ListAppender<>();

    @DynamicPropertySource
    static void backendUri(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + BACKEND.port());
    }

    @AfterAll
    static void stop() {
        BACKEND.disposeNow();
    }

    @BeforeEach
    void attach() {
        securityEvents.start();
        accessEvents.start();
        securityLogger.addAppender(securityEvents);
        accessLogger.addAppender(accessEvents);
    }

    @AfterEach
    void detach() {
        securityLogger.detachAppender(securityEvents);
        accessLogger.detachAppender(accessEvents);
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void authFailureLandsInTheSecurityStreamOnly() {
        client().get().uri("/secured/x").exchange().expectStatus().isUnauthorized();

        assertThat(securityEvents.list).anySatisfy(event -> {
            assertThat(event.getLoggerName()).isEqualTo("gateway.security");
            assertThat(event.getFormattedMessage()).contains("edge_auth_denied").contains("reason=unauthorized");
        });
        assertThat(accessEvents.list).noneMatch(event -> event.getFormattedMessage().contains("edge_auth_denied"));
    }

    @Test
    void routedRequestLandsInTheAccessStream() {
        client().get().uri("/path-route/x").exchange().expectStatus().isOk();

        awaitEvent("/path-route/x");
        assertThat(accessEvents.list).anySatisfy(event -> {
            assertThat(event.getLoggerName()).isEqualTo("gateway.access");
            assertThat(event.getFormattedMessage()).contains("/path-route/x");
        });
    }

    /** The access line is emitted in doFinally, which may complete just after the client sees the response. */
    private void awaitEvent(String fragment) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (accessEvents.list.stream().anyMatch(e -> e.getFormattedMessage().contains(fragment))) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
