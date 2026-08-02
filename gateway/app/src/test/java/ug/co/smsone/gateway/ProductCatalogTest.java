package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

/**
 * API products, catalog, and OpenAPI publish — on the admin (management) port. The catalog groups routes
 * into their product (a portal renders this); the OpenAPI document publishes the gateway's route paths.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "management.server.port=0")
class ProductCatalogTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> response.status(200).sendString(Mono.just("ok")))
            .bindNow();

    @Value("${local.management.port}")
    private int adminPort;

    @DynamicPropertySource
    static void backendUri(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + BACKEND.port());
    }

    @AfterAll
    static void stop() {
        BACKEND.disposeNow();
    }

    private WebTestClient admin() {
        return WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                .baseUrl("http://localhost:" + adminPort).build();
    }

    @Test
    void catalogGroupsRoutesUnderTheirProduct() {
        var result = admin().get().uri("/actuator/gatewaycatalog").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[?(@.id == 'sms-api')]").exists()
                .returnResult();

        String body = new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
        assertThat(body).contains("SMS API").contains("by-path"); // the product's display name + a member route
    }

    @Test
    void openApiPublishesTheRoutePaths() {
        var result = admin().get().uri("/actuator/gatewayopenapi").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.openapi").isEqualTo("3.0.3")
                .returnResult();

        String body = new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
        assertThat(body).contains("/path-route/**"); // a route's path is in the published spec
    }
}
