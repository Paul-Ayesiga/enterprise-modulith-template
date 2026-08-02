package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.route.ServiceResolver;

/**
 * Service discovery: a backend that arrives from a discovery source (not from config) is registered
 * automatically at startup, and a route already targeting that service — which was skipped while the
 * service was unknown — starts resolving. The disc-route (config) points at "discovered-svc"; the test
 * ServiceResolver reports it, and the request then routes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceDiscoveryTest.Discovery.class)
class ServiceDiscoveryTest {

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> response.status(200).sendString(Mono.just("ok")))
            .bindNow();

    @TestConfiguration
    static class Discovery {
        @Bean
        ServiceResolver testDiscoverySource() {
            return () -> List.of(new ServiceDefinition("discovered-svc",
                    List.of(URI.create("http://localhost:" + BACKEND.port())), "/health"));
        }
    }

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

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
    void aDiscoveredServiceMakesItsRouteResolve() {
        assertThat(awaitStatus("/disc/x", 200)).as("the route to the discovered service resolves").isTrue();
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
