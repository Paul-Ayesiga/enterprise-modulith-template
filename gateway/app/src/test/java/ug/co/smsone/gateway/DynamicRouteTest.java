package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import ug.co.smsone.gateway.core.route.RouteDefinition;
import ug.co.smsone.gateway.core.route.RoutePredicate;
import ug.co.smsone.gateway.core.route.RouteRegistrar;
import ug.co.smsone.gateway.core.security.AuthPolicy;
import ug.co.smsone.gateway.core.traffic.TrafficPolicy;
import ug.co.smsone.gateway.core.transform.TransformPolicy;

/**
 * Dynamic routes: a route added through the {@link RouteRegistrar} at runtime takes effect with no
 * restart (a RefreshRoutesEvent rebuilds the route table), and a dynamically-added authenticated route
 * is enforced — proving the edge re-applies policies too, not just the route table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DynamicRouteTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> response.status(200).sendString(Mono.just("ok")))
            .bindNow();

    @Value("${local.server.port}")
    private int gatewayPort;

    @Autowired
    private RouteRegistrar registrar;

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
    void addedRouteTakesEffectWithoutRestart() {
        client().get().uri("/dynamic/x").exchange().expectStatus().isNotFound(); // not registered yet

        registrar.register(new RouteDefinition("dyn-route", 100,
                List.of(new RoutePredicate(RoutePredicate.Kind.PATH, List.of("/dynamic/**"))),
                "backend", AuthPolicy.OPEN, TrafficPolicy.NONE, TransformPolicy.NONE, Map.of()));

        assertThat(awaitStatus("/dynamic/x", 200)).as("the added route now routes").isTrue();
    }

    @Test
    void addedAuthenticatedRouteIsEnforced() {
        registrar.register(new RouteDefinition("dynsec-route", 101,
                List.of(new RoutePredicate(RoutePredicate.Kind.PATH, List.of("/dynsec/**"))),
                "backend", new AuthPolicy(true, Set.of(), null), TrafficPolicy.NONE, TransformPolicy.NONE, Map.of()));

        // The route exists AND the refreshed policy enforces it: no token → 401 (not 200, not 404).
        assertThat(awaitStatus("/dynsec/x", 401)).as("the added authenticated route is enforced").isTrue();
    }

    private boolean awaitStatus(String uri, int expected) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            int status = client().get().uri(uri).exchange().returnResult(String.class).getStatus().value();
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
