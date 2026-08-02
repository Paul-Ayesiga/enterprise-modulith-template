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
 * Named, reusable policy sets: a policy (auth/traffic/transform) is defined once under
 * {@code gateway.policies.<name>} and attached to routes by {@code policy-ref} — no repeated config. Two
 * routes share one named transform policy; a route references a named auth policy and is enforced; and a
 * route that references a policy but overrides an aspect inline wins with its own config.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NamedPolicyTest {

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
    void twoRoutesShareOneNamedTransformPolicy() {
        // Both routes reference open-headers; changing that one policy would change both, with no route edits.
        client().get().uri("/pa/x").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Policy", "open");
        client().get().uri("/pb/x").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Policy", "open");
    }

    @Test
    void referencedAuthPolicyIsEnforced() {
        client().get().uri("/pc/x").exchange().expectStatus().isUnauthorized(); // must-auth, no token
    }

    @Test
    void inlineConfigOverridesTheReferencedPolicy() {
        // pd references must-auth but overrides auth inline to open → passes without a token.
        client().get().uri("/pd/x").exchange().expectStatus().isOk();
    }
}
