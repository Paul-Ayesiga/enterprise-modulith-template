package ug.co.smsone.gateway;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import ug.co.smsone.gateway.core.quota.ConsumerResolver;
import ug.co.smsone.gateway.core.quota.Quota;
import ug.co.smsone.gateway.core.quota.QuotaProvider;

/**
 * Quota enforcement: a consumer with a plan quota of 2 gets its first two calls, and the third is 429.
 * The QuotaProvider (here a stub standing in for the platform adapter) supplies the ceiling; a real
 * Valkey counts the calls in the window. The plumbing to the subscription plan is covered on the
 * modulith side (GatewayQuotaTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.TestPropertySource(properties = "management.server.port=0")
@Import(QuotaTest.StubQuota.class)
class QuotaTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);
    private static final DisposableServer BACKEND;

    static {
        VALKEY.start();
        BACKEND = HttpServer.create().port(0)
                .handle((request, response) -> response.status(200).sendString(Mono.just("ok")))
                .bindNow();
    }

    @TestConfiguration
    static class StubQuota {
        @Bean
        @Primary
        ConsumerResolver stubConsumerResolver() {
            return exchange -> Mono.just("quota-test-org"); // fresh Valkey per run, so a fixed id is fine
        }

        @Bean
        QuotaProvider stubQuotaProvider() {
            return consumer -> Mono.just(new Quota(2, Duration.ofMinutes(1)));
        }
    }

    @Value("${local.server.port}")
    private int gatewayPort;

    @Value("${local.management.port}")
    private int adminPort;

    private WebTestClient client;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("backend.uri", () -> "http://localhost:" + BACKEND.port());
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        BACKEND.disposeNow();
        VALKEY.stop();
    }

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                    .baseUrl("http://localhost:" + gatewayPort).build();
        }
        return client;
    }

    @Test
    void enforcesTheConsumerQuotaFromTheProviderAndReportsUsage() {
        client().get().uri("/quota/x").exchange().expectStatus().isOk();       // 1 of 2
        client().get().uri("/quota/x").exchange().expectStatus().isOk();       // 2 of 2
        client().get().uri("/quota/x").exchange()                              // over the ceiling
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("RATE_LIMITED");

        // The usage endpoint reports the SAME window counter the filter enforced against:
        // 3 attempts (the denied one counts — it incremented before the ceiling check), limit 2.
        WebTestClient admin = WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                .baseUrl("http://localhost:" + adminPort).build();
        admin.get().uri("/actuator/gatewayusage/quota-test-org").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.consumer").isEqualTo("quota-test-org")
                .jsonPath("$.used").isEqualTo(3)
                .jsonPath("$.limited").isEqualTo(true)
                .jsonPath("$.limit").isEqualTo(2)
                .jsonPath("$.remaining").isEqualTo(0);
        admin.get().uri("/actuator/gatewayusage").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.consumer == 'quota-test-org' && @.used == 3)]").exists();
    }
}
