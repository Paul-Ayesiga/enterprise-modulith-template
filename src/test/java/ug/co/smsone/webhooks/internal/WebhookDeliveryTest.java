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

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private double deadLettered() {
        io.micrometer.core.instrument.Counter counter = meters.find("smsone.deliveries.dead_lettered")
                .tag("queue", "webhook").tag("reason", "exhausted").counter();
        return counter == null ? 0 : counter.count();
    }

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
            WebhookSubscriptionService.CreatedSubscription created = subscriptions.create(orgId,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", Set.of("org.member.added"));
            WebhookSubscription subscription = created.subscription();

            dispatcher.dispatch("m-" + UUID.randomUUID(), orgId, "org.member.added",
                    WebhookPayload.of("org.member.added", orgId, Instant.now())
                            .with("subject", "kc-newbie").with("role", "MEMBER"));
            worker.drainOnce();

            assertThat(body.get()).contains("org.member.added").contains("kc-newbie").contains("MEMBER");
            assertThat(eventHeader.get()).isEqualTo("org.member.added");
            // Verified with the PLAINTEXT from the create result: the row holds only ciphertext,
            // and the receiver-side check proves the sender decrypts before signing.
            assertThat(signature.get()).isEqualTo("sha256=" + WebhookSigner.sign(created.plainSecret(), body.get()));
            assertThat(subscription.getSecret()).startsWith("enc:v1:");
            assertThat(deliveryStatus(subscription.getId())).isEqualTo("DELIVERED");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transientFailureIsRetriedThenDeadLettered() throws Exception {
        double deadLetteredBefore = deadLettered();
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
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/flaky", Set.of("org.status_changed")).subscription();

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
            assertThat(deadLettered()).as("the give-up is counted, not just logged")
                    .isEqualTo(deadLetteredBefore + 1);
        } finally {
            server.stop(0);
        }
    }

    /**
     * DELETE is the only revocation the API offers, and before soft delete it was airtight: the row
     * went away and {@code webhook_delivery}'s {@code on delete cascade} took the queue with it. The
     * row now survives, so the cascade never fires — without an explicit stop, everything already
     * enqueued keeps being POSTed to the endpoint the tenant just revoked, signed with the secret they
     * just rotated away from, for as long as the retry schedule lasts.
     */
    @Test
    void deletingASubscriptionStopsDeliveriesAlreadyQueued() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/revoked", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            UUID orgId = UUID.randomUUID();
            WebhookSubscription subscription = subscriptions.create(orgId,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/revoked", Set.of("org.member.added")).subscription();
            dispatcher.dispatch("m-" + UUID.randomUUID(), orgId, "org.member.added",
                    WebhookPayload.of("org.member.added", orgId, Instant.now()).with("subject", "kc-newbie"));

            subscriptions.delete(orgId, subscription.getId());
            worker.drainOnce();

            assertThat(hits.get()).isZero();
            assertThat(deliveryStatus(subscription.getId())).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select last_error from webhook_delivery where subscription_id = ?",
                    String.class, subscription.getId())).isEqualTo("subscription deleted");
        } finally {
            server.stop(0);
        }
    }

    /**
     * The claim predicate on its own. The test above also passes if only the cancellation half lands,
     * so this one leaves the delivery PENDING — stamping {@code deleted_at} behind the service's back —
     * and asserts the worker still refuses to pick it up. The claim is raw JDBC, which
     * {@code @SQLRestriction} cannot reach, so nothing else in the codebase enforces this.
     */
    @Test
    void aPendingDeliveryForADeletedSubscriptionIsNeverClaimed() throws Exception {
        UUID orgId = UUID.randomUUID();
        WebhookSubscription subscription = subscriptions.create(orgId,
                "http://127.0.0.1:1/unreachable", Set.of("org.member.added")).subscription();
        dispatcher.dispatch("m-" + UUID.randomUUID(), orgId, "org.member.added",
                WebhookPayload.of("org.member.added", orgId, Instant.now()).with("subject", "kc-newbie"));
        jdbc.update("update webhook_subscription set deleted_at = now() where id = ?", subscription.getId());

        worker.drainOnce();

        assertThat(deliveryStatus(subscription.getId())).isEqualTo("PENDING"); // never claimed, never attempted
        assertThat(jdbc.queryForObject("select attempts from webhook_delivery where subscription_id = ?",
                Integer.class, subscription.getId())).isZero();
    }

    /**
     * DISABLED is the softer revocation and must stop queued rows too, not just future fan-out —
     * unlike a delete, though, paused rows stay PENDING and resume on re-enable. The claim's status
     * predicate is raw JDBC, which nothing else in the codebase enforces.
     */
    @Test
    void disablingASubscriptionPausesQueuedDeliveriesUntilReenabled() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/paused", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            UUID orgId = UUID.randomUUID();
            WebhookSubscription subscription = subscriptions.create(orgId,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/paused", Set.of("org.member.added")).subscription();
            dispatcher.dispatch("m-" + UUID.randomUUID(), orgId, "org.member.added",
                    WebhookPayload.of("org.member.added", orgId, Instant.now()).with("subject", "kc-newbie"));
            jdbc.update("update webhook_subscription set status = 'DISABLED' where id = ?", subscription.getId());

            worker.drainOnce();
            assertThat(hits.get()).as("a paused subscription must not be POSTed").isZero();
            assertThat(deliveryStatus(subscription.getId())).isEqualTo("PENDING");

            jdbc.update("update webhook_subscription set status = 'ACTIVE' where id = ?", subscription.getId());
            worker.drainOnce();
            assertThat(hits.get()).as("re-enabling resumes what was queued").isEqualTo(1);
            assertThat(deliveryStatus(subscription.getId())).isEqualTo("DELIVERED");
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
