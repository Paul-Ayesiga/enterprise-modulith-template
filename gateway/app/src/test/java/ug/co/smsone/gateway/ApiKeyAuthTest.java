package ug.co.smsone.gateway;

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
 * API-key authentication via the platform adapter (the first `gateway:platform-adapter`): an
 * X-Api-Key is resolved by calling the platform's introspection endpoint (here a stub presenting the
 * shared secret), and the resolved principal drives the same coarse policy as a JWT. A valid key
 * routes and stamps identity; an inactive key is 401; a key missing a required scope or on the wrong
 * tenant is 403. When no introspector is configured (the other test classes), this path is simply off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyAuthTest {

    private static final String GATEWAY_SECRET = "test-secret";
    private static final DisposableServer SERVER;

    static {
        SERVER = HttpServer.create().port(0)
                .route(routes -> routes
                        .post("/introspect", (request, response) -> {
                            if (!GATEWAY_SECRET.equals(request.requestHeaders().get("X-Gateway-Secret"))) {
                                return response.status(401).send();
                            }
                            return request.receive().aggregate().asString().defaultIfEmpty("").flatMap(body -> {
                                String json;
                                if (body.contains("sk_valid")) {
                                    json = "{\"active\":true,\"subject\":\"key:123\",\"tenant\":\"acme\",\"scopes\":[\"api\"]}";
                                } else if (body.contains("sk_noscope")) {
                                    json = "{\"active\":true,\"subject\":\"key:456\",\"tenant\":\"acme\",\"scopes\":[]}";
                                } else {
                                    json = "{\"active\":false}";
                                }
                                return response.status(200).header("Content-Type", "application/json")
                                        .sendString(Mono.just(json)).then();
                            });
                        })
                        .route(request -> true, (request, response) -> response.status(200)
                                .header("X-Backend-Saw-Subject", headerOrNone(request, "X-Auth-Subject"))
                                .header("X-Backend-Saw-Tenant", headerOrNone(request, "X-Tenant-Id"))
                                .sendString(Mono.just("backend:" + request.uri()))))
                .bindNow();
    }

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + SERVER.port());
        registry.add("gateway.platform.introspection.uri", () -> "http://localhost:" + SERVER.port() + "/introspect");
        registry.add("gateway.platform.secret", () -> GATEWAY_SECRET);
    }

    @AfterAll
    static void stop() {
        SERVER.disposeNow();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void validApiKeyRoutesAndStampsIdentity() {
        client().get().uri("/secured/x").header("X-Api-Key", "sk_valid.secret").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Backend-Saw-Subject", "key:123")
                .expectHeader().valueEquals("X-Backend-Saw-Tenant", "acme");
    }

    @Test
    void inactiveApiKeyIs401() {
        client().get().uri("/secured/x").header("X-Api-Key", "sk_invalid").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void apiKeyWithRequiredScopePasses() {
        client().get().uri("/scoped/x").header("X-Api-Key", "sk_valid.secret").exchange()
                .expectStatus().isOk();
    }

    @Test
    void apiKeyMissingRequiredScopeIs403() {
        client().get().uri("/scoped/x").header("X-Api-Key", "sk_noscope").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("FORBIDDEN");
    }

    @Test
    void apiKeyMatchingTenantPasses() {
        client().get().uri("/tenant/orgs/acme/data").header("X-Api-Key", "sk_valid.secret").exchange()
                .expectStatus().isOk();
    }

    @Test
    void apiKeyWrongTenantIs403() {
        client().get().uri("/tenant/orgs/globex/data").header("X-Api-Key", "sk_valid.secret").exchange()
                .expectStatus().isForbidden();
    }

    private static String headerOrNone(HttpServerRequest request, String name) {
        HttpHeaders headers = request.requestHeaders();
        String value = headers.get(name);
        return value == null ? "none" : value;
    }
}
