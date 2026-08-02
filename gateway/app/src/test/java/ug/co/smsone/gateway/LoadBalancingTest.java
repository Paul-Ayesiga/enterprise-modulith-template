package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
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
 * Load-balancing: a service declared with two instances is routed as lb://service, and Spring Cloud
 * LoadBalancer spreads requests across both. Two reactor-netty stubs each answer with their own name;
 * a run of requests to the one route must reach both — proving the edge is not pinned to one instance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoadBalancingTest {

    private static final DisposableServer INSTANCE_A = instance("A");
    private static final DisposableServer INSTANCE_B = instance("B");

    private static DisposableServer instance(String name) {
        return HttpServer.create().port(0)
                .handle((request, response) -> response.status(200).sendString(Mono.just(name)))
                .bindNow();
    }

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

    @DynamicPropertySource
    static void instanceUris(DynamicPropertyRegistry registry) {
        registry.add("lb.a.uri", () -> "http://localhost:" + INSTANCE_A.port());
        registry.add("lb.b.uri", () -> "http://localhost:" + INSTANCE_B.port());
    }

    @AfterAll
    static void stop() {
        INSTANCE_A.disposeNow();
        INSTANCE_B.disposeNow();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void requestsAreSpreadAcrossBothInstances() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            String body = client().get().uri("/lb/x").exchange()
                    .expectStatus().isOk()
                    .returnResult(String.class).getResponseBody().blockFirst();
            seen.add(body);
        }
        assertThat(seen).as("both lb-backend instances were used").containsExactlyInAnyOrder("A", "B");
    }
}
