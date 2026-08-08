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
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The webhook delivery pipeline against a REAL local receiver: fan-out enqueues, the worker signs and
 * POSTs, a 2xx marks DELIVERED, and a 5xx is retried with backoff until dead-lettered. Verifies the
 * HMAC-SHA256 signature and headers the receiver sees.
 *
 * <p><b>Everything this class touches is TENANT-tier</b> — {@code webhook_subscription} and
 * {@code webhook_delivery} both (ADR 0010 §2) — so every call that reaches the database is pinned to
 * the org it is about, with {@code TenantContext.runAs/callAs(orgId, …)}. The harness pins PLATFORM,
 * where neither table resolves at all; the service calls need it too, because the pin has to be
 * declared before their {@code @Transactional} boundary borrows a connection.
 *
 * <p>The org ids here are bare uuids with no {@code organization} row behind them, and that keeps
 * working: an org that was never promoted resolves to {@code tenant_pool}, and one that does not exist
 * can only ever be unpromoted. The subscription rows are the tenancy this test needs; the routing
 * registry is not.
 *
 * <p>{@code worker.drainOnce()} is deliberately NOT pinned anywhere below. Declaring its own axis is
 * the worker's obligation (ADR 0010 §3.4) and this class is where it is really tested — the poller is
 * a thread the worker starts itself, which no filter and no {@code TaskDecorator} ever covers.
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
            WebhookSubscriptionService.CreatedSubscription created = TenantContext.callAs(orgId,
                    () -> subscriptions.create(orgId,
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                            Set.of("org.member.added")));
            WebhookSubscription subscription = created.subscription();

            TenantContext.runAs(orgId, () -> dispatcher.dispatch("m-" + UUID.randomUUID(), orgId,
                    "org.member.added", WebhookPayload.of("org.member.added", orgId, Instant.now())
                            .with("subject", "kc-newbie").with("role", "MEMBER")));
            worker.drainOnce();

            assertThat(body.get()).contains("org.member.added").contains("kc-newbie").contains("MEMBER");
            assertThat(eventHeader.get()).isEqualTo("org.member.added");
            // Verified with the PLAINTEXT from the create result: the row holds only ciphertext,
            // and the receiver-side check proves the sender decrypts before signing.
            assertThat(signature.get()).isEqualTo("sha256=" + WebhookSigner.sign(created.plainSecret(), body.get()));
            assertThat(subscription.getSecret()).startsWith("enc:v1:");
            assertThat(deliveryStatus(orgId, subscription.getId())).isEqualTo("DELIVERED");
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
            WebhookSubscription subscription = TenantContext.callAs(orgId, () -> subscriptions.create(orgId,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/flaky",
                    Set.of("org.status_changed"))).subscription();

            TenantContext.runAs(orgId, () -> dispatcher.dispatch("m-" + UUID.randomUUID(), orgId,
                    "org.status_changed",
                    WebhookPayload.of("org.status_changed", orgId, Instant.now()).with("status", "SUSPENDED")));

            // Fast-forward the backoff and drain until dead-lettered (max-attempts default = 5).
            // Everything in this block runs on Awaitility's own thread, which carries no tenant axis
            // (ADR 0010 §3.4): the fast-forward declares one — the TENANT's, since that is where the
            // row is — and the drain deliberately does not, because that is the worker's own
            // obligation and this is the one place it is really tested.
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                TenantContext.runAs(orgId, () -> jdbc.update(
                        "update webhook_delivery set next_attempt_at = now() where subscription_id = ?",
                        subscription.getId()));
                worker.drainOnce();
                assertThat(deliveryStatus(orgId, subscription.getId())).isEqualTo("FAILED");
            });
            assertThat(hits.get()).isEqualTo(5);
            Integer responseStatus = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                    "select response_status from webhook_delivery where subscription_id = ?",
                    Integer.class, subscription.getId()));
            assertThat(responseStatus).isEqualTo(503);
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
            WebhookSubscription subscription = TenantContext.callAs(orgId, () -> subscriptions.create(orgId,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/revoked",
                    Set.of("org.member.added"))).subscription();
            TenantContext.runAs(orgId, () -> dispatcher.dispatch("m-" + UUID.randomUUID(), orgId,
                    "org.member.added",
                    WebhookPayload.of("org.member.added", orgId, Instant.now()).with("subject", "kc-newbie")));

            TenantContext.runAs(orgId, () -> subscriptions.delete(orgId, subscription.getId()));
            worker.drainOnce();

            assertThat(hits.get()).isZero();
            assertThat(deliveryStatus(orgId, subscription.getId())).isEqualTo("FAILED");
            String lastError = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                    "select last_error from webhook_delivery where subscription_id = ?",
                    String.class, subscription.getId()));
            assertThat(lastError).isEqualTo("subscription deleted");
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
        WebhookSubscription subscription = TenantContext.callAs(orgId, () -> subscriptions.create(orgId,
                "http://127.0.0.1:1/unreachable", Set.of("org.member.added"))).subscription();
        TenantContext.runAs(orgId, () -> {
            dispatcher.dispatch("m-" + UUID.randomUUID(), orgId, "org.member.added",
                    WebhookPayload.of("org.member.added", orgId, Instant.now()).with("subject", "kc-newbie"));
            jdbc.update("update webhook_subscription set deleted_at = now() where id = ?", subscription.getId());
        });

        worker.drainOnce();

        // never claimed, never attempted
        assertThat(deliveryStatus(orgId, subscription.getId())).isEqualTo("PENDING");
        Integer attempts = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select attempts from webhook_delivery where subscription_id = ?",
                Integer.class, subscription.getId()));
        assertThat(attempts).isZero();
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
            WebhookSubscription subscription = TenantContext.callAs(orgId, () -> subscriptions.create(orgId,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/paused",
                    Set.of("org.member.added"))).subscription();
            TenantContext.runAs(orgId, () -> {
                dispatcher.dispatch("m-" + UUID.randomUUID(), orgId, "org.member.added",
                        WebhookPayload.of("org.member.added", orgId, Instant.now()).with("subject", "kc-newbie"));
                jdbc.update("update webhook_subscription set status = 'DISABLED' where id = ?",
                        subscription.getId());
            });

            worker.drainOnce();
            assertThat(hits.get()).as("a paused subscription must not be POSTed").isZero();
            assertThat(deliveryStatus(orgId, subscription.getId())).isEqualTo("PENDING");

            TenantContext.runAs(orgId, () -> jdbc.update(
                    "update webhook_subscription set status = 'ACTIVE' where id = ?", subscription.getId()));
            worker.drainOnce();
            assertThat(hits.get()).as("re-enabling resumes what was queued").isEqualTo(1);
            assertThat(deliveryStatus(orgId, subscription.getId())).isEqualTo("DELIVERED");
        } finally {
            server.stop(0);
        }
    }

    /**
     * A paused subscription must not be able to starve everyone else's deliveries.
     *
     * <p>The claim used to apply the subscription-liveness filter in its OUTER join, after an inner
     * {@code order by next_attempt_at limit <batchSize>} that knew nothing about it. So a paused
     * subscription holding the oldest {@code batchSize} rows filled every claim slot with rows the outer
     * filter then discarded: every poll claimed zero, and — because the stuck rows are never claimed,
     * never rescheduled and never dead-lettered — webhook delivery stopped for the ENTIRE PLATFORM,
     * permanently, not just for that tenant.
     *
     * <p>{@code disablingASubscriptionPausesQueuedDeliveriesUntilReenabled} above asserts the pause
     * itself and passed throughout, because it queues ONE delivery and one row can never fill the LIMIT
     * window. That is precisely why this shipped, so this test's whole point is the VOLUME: it queues
     * more than {@code batchSize} paused rows and then asserts a healthy tenant behind them still gets
     * delivered.
     */
    @Test
    void aPausedSubscriptionCannotStarveEveryOtherTenantsDeliveries() throws Exception {
        int batchSize = 50; // app.webhooks.batch-size default; the window that used to be filled
        AtomicInteger healthyHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthy", exchange -> {
            healthyHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            UUID pausedOrg = UUID.randomUUID();
            WebhookSubscription paused = TenantContext.callAs(pausedOrg, () -> subscriptions.create(pausedOrg,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/never",
                    Set.of("org.member.added"))).subscription();
            TenantContext.runAs(pausedOrg, () -> {
                for (int i = 0; i < batchSize + 10; i++) {
                    dispatcher.dispatch("starve-" + UUID.randomUUID(), pausedOrg, "org.member.added",
                            WebhookPayload.of("org.member.added", pausedOrg, Instant.now()));
                }
                jdbc.update("update webhook_subscription set status = 'DISABLED' where id = ?", paused.getId());
                // Age them so they sort to the head of the queue — the position that did the damage.
                jdbc.update("update webhook_delivery set next_attempt_at = now() - interval '1 day' "
                        + "where subscription_id = ?", paused.getId());
            });

            // A different org — and, while the pool is one schema, the same queue. That is exactly the
            // starvation this test is about: the two tenants' rows compete for one claim's LIMIT.
            UUID healthyOrg = UUID.randomUUID();
            WebhookSubscription healthy = TenantContext.callAs(healthyOrg, () -> subscriptions.create(healthyOrg,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/healthy",
                    Set.of("org.member.added"))).subscription();
            TenantContext.runAs(healthyOrg, () -> dispatcher.dispatch("healthy-" + UUID.randomUUID(),
                    healthyOrg, "org.member.added",
                    WebhookPayload.of("org.member.added", healthyOrg, Instant.now())));

            worker.drainOnce();

            assertThat(healthyHits.get())
                    .as("a paused tenant's backlog must not block a healthy tenant's delivery")
                    .isEqualTo(1);
            assertThat(deliveryStatus(healthyOrg, healthy.getId())).isEqualTo("DELIVERED");
            Integer consumed = TenantContext.callAs(pausedOrg, () -> jdbc.queryForObject(
                    "select count(*) from webhook_delivery where subscription_id = ? and status <> 'PENDING'",
                    Integer.class, paused.getId()));
            assertThat(consumed)
                    .as("the paused subscription's rows stay PENDING — paused, not consumed")
                    .isZero();
        } finally {
            server.stop(0);
        }
    }

    /**
     * The other half of the same claim, and the failure mode a naive fix introduces: splitting the claim
     * into "PENDING first, stale only if that came up short" means a permanent PENDING backlog stops
     * crashed PROCESSING rows ever being reclaimed — trading a performance bug for a lost delivery. The
     * two arms each carry their own LIMIT so neither can crowd the other out.
     */
    @Test
    void aStaleInFlightDeliveryIsReclaimedEvenBehindADeepPendingBacklog() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stale", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            UUID orgId = UUID.randomUUID();
            WebhookSubscription subscription = TenantContext.callAs(orgId, () -> subscriptions.create(orgId,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/stale",
                    Set.of("org.member.added"))).subscription();

            TenantContext.runAs(orgId, () -> {
                // One delivery that a crashed instance left holding the lock.
                dispatcher.dispatch("stale-" + UUID.randomUUID(), orgId, "org.member.added",
                        WebhookPayload.of("org.member.added", orgId, Instant.now()));
                jdbc.update("update webhook_delivery set status = 'PROCESSING', "
                        + "locked_at = now() - interval '1 hour' where subscription_id = ?", subscription.getId());

                // A deep PENDING backlog in front of it, all newer.
                for (int i = 0; i < 60; i++) {
                    dispatcher.dispatch("backlog-" + UUID.randomUUID(), orgId, "org.member.added",
                            WebhookPayload.of("org.member.added", orgId, Instant.now()));
                }
            });

            worker.drainOnce();

            Integer stranded = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                    "select count(*) from webhook_delivery where subscription_id = ? and status = 'PROCESSING' "
                            + "and locked_at < now() - interval '30 minutes'", Integer.class, subscription.getId()));
            assertThat(stranded)
                    .as("the stale in-flight row must be reclaimed, not stranded behind the backlog")
                    .isZero();
        } finally {
            server.stop(0);
        }
    }

    /**
     * Pinned twice over. {@code webhook_delivery} is TENANT-tier, so the org is what the read needs —
     * and one caller reads it from inside {@code await().untilAsserted(…)}, where Awaitility polls on
     * ITS OWN thread and the harness has pinned nothing at all, so the borrow would route to the empty
     * {@code no_tenant} schema (ADR 0010 §3.4). Declared here rather than at that one call site so
     * every reader of this row goes through the same axis.
     *
     * <p>{@code orgId} is a parameter rather than a field because the starvation test deliberately has
     * two of them, and reading one tenant's row on the other's axis is precisely the mistake this whole
     * mechanism exists to make impossible.
     */
    private String deliveryStatus(UUID orgId, UUID subscriptionId) {
        return TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select status from webhook_delivery where subscription_id = ? order by created_at desc limit 1",
                String.class, subscriptionId));
    }
}
