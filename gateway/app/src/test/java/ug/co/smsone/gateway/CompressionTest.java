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
 * Response compression: the gateway gzips a proxied response the client will accept, but only above the
 * configured min size and only when the client sends Accept-Encoding. The backend stub returns a large
 * JSON body for /path-route/big and a tiny one for /path-route/small; the non-pooling client never adds
 * Accept-Encoding on its own, so each case controls it explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompressionTest {

    private static final String BIG_BODY = "{\"data\":\"" + "a".repeat(4096) + "\"}";
    private static final DisposableServer BACKEND;

    static {
        BACKEND = HttpServer.create().port(0)
                .route(routes -> routes
                        .route(request -> request.uri().contains("big"), (request, response) ->
                                response.status(200).header("Content-Type", "application/json")
                                        .sendString(Mono.just(BIG_BODY)))
                        .route(request -> true, (request, response) ->
                                response.status(200).header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"ok\":true}"))))
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
    void largeResponseIsGzippedWhenAccepted() {
        client().get().uri("/path-route/big").header("Accept-Encoding", "gzip").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Encoding", "gzip");
    }

    @Test
    void notCompressedWithoutAcceptEncoding() {
        client().get().uri("/path-route/big").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Content-Encoding");
    }

    @Test
    void smallResponseIsNotCompressed() {
        client().get().uri("/path-route/small").header("Accept-Encoding", "gzip").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Content-Encoding");
    }
}
