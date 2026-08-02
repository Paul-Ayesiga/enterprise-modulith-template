package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import ug.co.smsone.gateway.core.plugin.GatewayPlugin;
import ug.co.smsone.gateway.core.plugin.PluginChain;

/**
 * The plugin framework: a custom {@link GatewayPlugin} bean is discovered and runs in the pipeline; a
 * plugin can mutate the request; config enables/disables and reorders plugins with no code. Three test
 * plugins record their run order; config disables plugin-c and moves plugin-a ahead of plugin-b.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PluginTest.TestPlugins.class)
@TestPropertySource(properties = {
        "gateway.plugins.plugin-a.order=5",     // ahead of plugin-b's default 20
        "gateway.plugins.plugin-c.enabled=false" // disabled — must not run
})
class PluginTest {

    static final List<String> EXECUTION = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class TestPlugins {
        @Bean
        GatewayPlugin pluginA() {
            return plugin("plugin-a", 10, exchange -> exchange.mutate()
                    .request(request -> request.headers(headers -> headers.set("X-Plugin-Mark", "marked")))
                    .build());
        }

        @Bean
        GatewayPlugin pluginB() {
            return plugin("plugin-b", 20, exchange -> exchange);
        }

        @Bean
        GatewayPlugin pluginC() {
            return plugin("plugin-c", 30, exchange -> exchange);
        }

        private static GatewayPlugin plugin(String name, int order,
                java.util.function.Function<ServerWebExchange, ServerWebExchange> transform) {
            return new GatewayPlugin() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public int defaultOrder() {
                    return order;
                }

                @Override
                public Mono<Void> filter(ServerWebExchange exchange, PluginChain chain) {
                    EXECUTION.add(name);
                    return chain.next(transform.apply(exchange));
                }
            };
        }
    }

    private static final DisposableServer BACKEND = HttpServer.create().port(0)
            .handle((request, response) -> response.status(200)
                    .header("X-Saw-Mark", orNone(request.requestHeaders().get("X-Plugin-Mark")))
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

    @BeforeEach
    void clear() {
        EXECUTION.clear();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void enabledPluginsRunInConfiguredOrderAndCanMutateTheRequest() {
        var result = client().get().uri("/path-route/x").exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        // plugin-a ran and its request mutation reached the backend
        assertThat(result.getResponseHeaders().getFirst("X-Saw-Mark")).isEqualTo("marked");
        // a (order 5) before b (order 20); c is disabled
        assertThat(EXECUTION).containsExactly("plugin-a", "plugin-b");
    }

    @Test
    void disabledPluginNeverRuns() {
        client().get().uri("/path-route/x").exchange().expectStatus().isOk();
        assertThat(EXECUTION).doesNotContain("plugin-c");
    }

    private static String orNone(String value) {
        return value == null ? "none" : value;
    }
}
