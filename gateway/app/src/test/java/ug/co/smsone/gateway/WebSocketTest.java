package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * WebSocket pass-through: the edge proxies a WebSocket upgrade end to end (SCG's WebsocketRoutingFilter
 * rewrites http→ws to the backend). A reactor-netty WS backend echoes frames; a client opening a socket
 * through the gateway sends a message and receives the same message back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .route(routes -> routes.ws("/ws/echo", (in, out) -> out.send(in.receive().retain())))
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

    @Test
    void proxiesAWebSocketEndToEnd() {
        ReactorNettyWebSocketClient wsClient = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + gatewayPort + "/ws/echo");
        AtomicReference<String> received = new AtomicReference<>();

        wsClient.execute(uri, session -> session
                        .send(reactor.core.publisher.Mono.just(session.textMessage("ping")))
                        .thenMany(session.receive().take(1).map(WebSocketMessage::getPayloadAsText))
                        .doOnNext(received::set)
                        .then())
                .block(Duration.ofSeconds(5));

        assertThat(received.get()).as("the message echoed back through the gateway").isEqualTo("ping");
    }
}
