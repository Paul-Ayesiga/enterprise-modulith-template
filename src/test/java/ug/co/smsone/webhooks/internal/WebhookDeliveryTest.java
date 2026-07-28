package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The webhook delivery pipeline against a REAL local receiver: fan-out enqueues, the worker signs and
 * POSTs, a 2xx marks DELIVERED, and a 5xx is retried with backoff until dead-lettered. Verifies the
 * HMAC-SHA256 signature and headers the receiver sees.
 */
class WebhookDeliveryTest extends AbstractIntegrationTest {

    @Autowired
    private WebhookSubscriptionService subscriptions;

    @Autowired
    private WebhookDispatcher dispatcher;

    @Autowired
    private WebhookDeliveryWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void deliversASignedPayloadAndMarksDelivered() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> eventHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            signature.set(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
            eventHeader.set(exchange.getRequestHeaders().getFirst("X-Webhook-Event"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            UUID orgId = UUID.randomUUID();
            WebhookSubscription subscription = subscriptions.create(orgId,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", Set.of("org.member.added"));

            dispatcher.dispatch("m-" + UUID.randomUUID(), orgId, "org.member.added",
                    WebhookPayload.of("org.member.added", orgId, Instant.now())
                            .with("subject", "kc-newbie").with("role", "MEMBER"));
            worker.drainOnce();

            assertThat(body.get()).contains("org.member.added").contains("kc-newbie").contains("MEMBER");
            assertThat(eventHeader.get()).isEqualTo("org.member.added");
            assertThat(signature.get()).isEqualTo("sha256=" + WebhookSigner.sign(subscription.getSecret(), body.get()));
            assertThat(deliveryStatus(subscription.getId())).isEqualTo("DELIVERED");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transientFailureIsRetriedThenDeadLettered() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/flaky", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(503, -1); // transient
            exchange.close();
        });
        server.start();
        try {
            UUID orgId = UUID.randomUUID();
            WebhookSubscription subscription = subscriptions.create(orgId,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/flaky", Set.of("org.status_changed"));

            dispatcher.dispatch("m-" + UUID.randomUUID(), orgId, "org.status_changed",
                    WebhookPayload.of("org.status_changed", orgId, Instant.now()).with("status", "SUSPENDED"));

            // Fast-forward the backoff and drain until dead-lettered (max-attempts default = 5).
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                jdbc.update("update webhook_delivery set next_attempt_at = now() where subscription_id = ?",
                        subscription.getId());
                worker.drainOnce();
                assertThat(deliveryStatus(subscription.getId())).isEqualTo("FAILED");
            });
            assertThat(hits.get()).isEqualTo(5);
            assertThat(jdbc.queryForObject(
                    "select response_status from webhook_delivery where subscription_id = ?",
                    Integer.class, subscription.getId())).isEqualTo(503);
        } finally {
            server.stop(0);
        }
    }

    private String deliveryStatus(UUID subscriptionId) {
        return jdbc.queryForObject(
                "select status from webhook_delivery where subscription_id = ? order by created_at desc limit 1",
                String.class, subscriptionId);
    }
}
