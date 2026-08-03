package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 * The admin API on a separate port: the route-admin endpoint lives on the management port, isolated from
 * public traffic. An operator lists routes and creates one there (which then serves real requests on the
 * public port), while the public port never exposes the admin API at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "management.server.port=0") // admin on its own (random) port
class AdminApiTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> response.status(200).sendString(Mono.just("ok")))
            .bindNow();

    @Value("${local.server.port}")
    private int publicPort;

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

    private WebTestClient clientFor(int port) {
        return WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                .baseUrl("http://localhost:" + port).build();
    }

    @Test
    void listsRoutesOnTheAdminPort() {
        clientFor(adminPort).get().uri("/actuator/gatewayroutes").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[?(@.id == 'by-path')]").exists(); // a config-seeded route
    }

    @Test
    void createsARouteViaAdminThatPublicTrafficThenServes() {
        clientFor(publicPort).get().uri("/admincreated/x").exchange().expectStatus().isNotFound();

        clientFor(adminPort).post().uri("/actuator/gatewayroutes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("id", "admin-created", "path", "/admincreated/**", "serviceId", "backend"))
                .exchange()
                .expectStatus().isOk();

        assertThat(awaitStatus("/admincreated/x", 200)).as("the admin-created route now serves").isTrue();
    }

    @Test
    void updatesARouteInPlaceAndLifecyclePausesAndResumesTraffic() {
        clientFor(adminPort).post().uri("/actuator/gatewayroutes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("id", "admin-edited", "path", "/adminedited/**", "serviceId", "backend"))
                .exchange()
                .expectStatus().isOk();
        assertThat(awaitStatus("/adminedited/x", 200)).as("the created route serves").isTrue();

        // Edit in place: the path moves, the old one stops matching — no delete-and-recreate.
        clientFor(adminPort).post().uri("/actuator/gatewayroutes/admin-edited")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("path", "/adminmoved/**", "order", 900))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("updated");
        assertThat(awaitStatus("/adminmoved/x", 200)).as("the edited path serves").isTrue();
        assertThat(awaitStatus("/adminedited/x", 404)).as("the old path no longer matches").isTrue();

        // Pause = lifecycle RETIRED (the edge answers 410 Gone); resume = PUBLISHED serves again.
        clientFor(adminPort).post().uri("/actuator/gatewayroutes/admin-edited")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("lifecycle", "RETIRED"))
                .exchange()
                .expectStatus().isOk();
        assertThat(awaitStatus("/adminmoved/x", 410)).as("a RETIRED route pauses traffic").isTrue();

        clientFor(adminPort).post().uri("/actuator/gatewayroutes/admin-edited")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("lifecycle", "PUBLISHED"))
                .exchange()
                .expectStatus().isOk();
        assertThat(awaitStatus("/adminmoved/x", 200)).as("PUBLISHED resumes traffic").isTrue();

        // The read reflects the edit (path/order/lifecycle now first-class row fields).
        clientFor(adminPort).get().uri("/actuator/gatewayroutes").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.id == 'admin-edited' && @.order == 900 && @.lifecycle == 'PUBLISHED')]").exists()
                .jsonPath("$[?(@.id == 'admin-edited' && @.path == '/adminmoved/**')]").exists();
    }

    @Test
    void adminApiIsNotReachableOnThePublicPort() {
        // Actuator (incl. the admin API) is bound to the management port only — the edge has no such route.
        clientFor(publicPort).get().uri("/actuator/gatewayroutes").exchange().expectStatus().isNotFound();
    }

    private boolean awaitStatus(String uri, int expected) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            int status = clientFor(publicPort).get().uri(uri).exchange()
                    .returnResult(String.class).getStatus().value();
            if (status == expected) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
