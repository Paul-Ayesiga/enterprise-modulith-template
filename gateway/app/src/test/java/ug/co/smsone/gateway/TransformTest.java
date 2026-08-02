package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpHeaders;
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
import reactor.netty.http.server.HttpServerRequest;

/**
 * Request/response transformation, entirely by config (no code): the transform-route strips a path
 * prefix, injects X-Tenant-Injected, drops Authorization, adds a query param upstream, sets a response
 * security header, and strips an internal response header. The backend reflects what it received; the
 * assertions confirm each transform took effect at the edge.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransformTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> response.status(200)
                    .header("X-Saw-Uri", request.uri())
                    .header("X-Saw-Tenant-Injected", header(request, "X-Tenant-Injected"))
                    .header("X-Saw-Auth", request.requestHeaders().get("Authorization") == null ? "absent" : "present")
                    .header("X-Backend-Secret", "leak")
                    .sendString(Mono.just("ok")))
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
    void appliesRequestAndResponseTransforms() {
        var result = client().get().uri("/xform/foo")
                // Basic (not Bearer) so the resource server ignores it — we only test that it is stripped.
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Frame-Options", "DENY")   // response header set at the edge
                .expectHeader().doesNotExist("X-Backend-Secret")         // internal response header stripped
                .returnResult(String.class);

        var headers = result.getResponseHeaders();
        assertThat(headers.getFirst("X-Saw-Uri")).startsWith("/foo").contains("source=edge"); // strip-prefix + param
        assertThat(headers.getFirst("X-Saw-Tenant-Injected")).isEqualTo("acme");              // header injected
        assertThat(headers.getFirst("X-Saw-Auth")).isEqualTo("absent");                       // Authorization stripped
    }

    private static String header(HttpServerRequest request, String name) {
        HttpHeaders headers = request.requestHeaders();
        return headers.get(name) == null ? "none" : headers.get(name);
    }
}
