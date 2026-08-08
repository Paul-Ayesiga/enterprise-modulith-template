package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * <b>One tenant's backlog must not stop another tenant's webhooks — and this is a guarantee the code
 * did not have before ADR 0010 Phase 3.</b>
 *
 * <p>{@code WebhookDeliveryTest.aPausedSubscriptionCannotStarveEveryOtherTenantsDeliveries} pins the
 * closest thing that existed: rows that could never be claimed at all no longer occupy claim slots.
 * That fix was a PREDICATE — the subscription-liveness checks moved inside the limit — and it only
 * ever covered rows that were unclaimable. A tenant whose backlog is perfectly healthy was, and had to
 * be, served strictly oldest-first across the whole fleet: {@code order by next_attempt_at limit
 * batchSize} over one shared table means the tenant holding the oldest {@code batchSize} rows takes
 * every batch until it drains, and everyone else waits. Nothing in the codebase said otherwise and no
 * test could have caught it, because it was not a bug — it was the shape of the claim.
 *
 * <p>{@code platform.queue_signal} changes the shape. A drain claims a TENANT first and its rows
 * second, so one tenant can hold at most one claim slot, and the release stamps a serviced tenant with
 * {@code greatest(remaining, now())} — behind everyone who has been waiting longer. Fairness stops
 * being a property of an ordering someone could undo and becomes a property of how many rows a claim
 * can possibly return.
 *
 * <p><b>Written so it FAILS against the pre-signal claim.</b> The busy tenant's backlog is three
 * batches deep and strictly older than the quiet tenant's single delivery, so a fleet-wide
 * oldest-first claim spends both drains inside it and never reaches the quiet one — which is exactly
 * what {@link #busyStillHasABacklogAfterBothDrains} asserts, so the counterfactual is not a claim in a
 * comment but a checked fact. A test that passed either way would prove nothing here.
 *
 * <p>Everything below is pinned: {@code webhook_subscription} and {@code webhook_delivery} are both
 * tenant-tier (ADR 0010 §2), and the org ids are bare uuids with no {@code organization} row behind
 * them — an org that was never promoted resolves to {@code tenant_pool}, and one that does not exist
 * can only ever be unpromoted. {@code worker.drainOnce()} is deliberately NOT pinned: declaring its own
 * axis is the worker's obligation.
 */
class WebhookQueueFairnessTest extends AbstractIntegrationTest {

    private static final String EVENT = "org.member.added";

    /**
     * The axis {@link #resetQueue()} sweeps on. It names no organization deliberately: an org in no
     * {@code organization} row can only ever resolve to the shared {@code tenant_pool}, so this IS the
     * pooled schema's axis, spelled with the only vocabulary {@code TenantContext} has. ADR 0010
     * Phase 5 turns it into a loop over {@code platform.tenant_placement}.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

    @Autowired
    private WebhookSubscriptionService subscriptions;

    @Autowired
    private WebhookDeliveryQueue queue;

    @Autowired
    private WebhookDeliveryWorker worker;

    @Autowired
    private WebhookProperties config;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID busyOrg = UUID.randomUUID();
    private final UUID quietOrg = UUID.randomUUID();

    private final AtomicInteger busyHits = new AtomicInteger();
    private final AtomicInteger quietHits = new AtomicInteger();

    private HttpServer server;
    private UUID busySubscription;
    private UUID quietSubscription;

    /**
     * An empty queue AND an empty signal table, because a drain is now one tenant's batch: a leftover
     * tenant from another class that still has claimable rows would consume a whole drain, and this
     * test counts drains.
     */
    @BeforeEach
    void resetQueue() {
        TenantContext.runAs(POOLED_TENANT, () -> jdbc.update("delete from webhook_delivery"));
        jdbc.update("delete from platform.queue_signal where queue = 'webhook'");
    }

    @Test
    void aBusyTenantsBacklogDoesNotStopAQuietTenantsDelivery() throws Exception {
        int backlog = 3 * config.batchSize();
        withReceivers(() -> {
            seedBusyBacklog(backlog);
            seedQuietDelivery();

            // Drain one: the busy tenant is the oldest, so it is served first — that part is unchanged,
            // and it is what makes the second drain the whole test. Exactly one batch, not the whole
            // backlog: one tenant, one claim slot.
            assertThat(worker.drainOnce())
                    .as("a drain takes ONE tenant's batch, however deep that tenant's backlog is")
                    .isEqualTo(config.batchSize());
            assertThat(busyHits.get()).isEqualTo(config.batchSize());
            assertThat(quietHits.get()).as("the quiet tenant's turn has not come yet").isZero();

            // Drain two: the busy tenant was stamped to the back on release, so the quiet tenant — whose
            // signal has been due since before that stamp — is next. Against the pre-signal claim this
            // drain would have taken another batch of the busy tenant's still-older rows.
            assertThat(worker.drainOnce()).isEqualTo(1);
            assertThat(quietHits.get())
                    .as("a tenant with one delivery must not wait behind another tenant's backlog")
                    .isEqualTo(1);
            assertThat(busyHits.get()).as("and the busy tenant took no second batch").isEqualTo(config.batchSize());
            assertThat(statusOf(quietOrg, quietSubscription)).containsExactly("DELIVERED");
        });
    }

    /**
     * The counterfactual, checked rather than asserted in prose: after both drains the busy tenant
     * still holds rows that are OLDER than the quiet tenant's, which is precisely the state in which a
     * fleet-wide {@code order by next_attempt_at limit batchSize} would have claimed them again and
     * left the quiet tenant unserved. Without this, the test above would still pass if the fixture
     * happened to leave the busy tenant empty and would then be proving nothing.
     */
    @Test
    void busyStillHasABacklogAfterBothDrains() throws Exception {
        int backlog = 3 * config.batchSize();
        withReceivers(() -> {
            seedBusyBacklog(backlog);
            seedQuietDelivery();
            worker.drainOnce();
            worker.drainOnce();

            Integer olderThanTheQuietOne = TenantContext.callAs(busyOrg, () -> jdbc.queryForObject("""
                    select count(*) from webhook_delivery
                     where org_id = ? and status = 'PENDING'
                       and next_attempt_at < (select min(next_attempt_at) from webhook_delivery
                                               where org_id = ?)
                    """, Integer.class, busyOrg, quietOrg));
            assertThat(olderThanTheQuietOne)
                    .as("the busy tenant must still hold rows a fleet-wide oldest-first claim would "
                            + "have preferred — otherwise the fairness test above proves nothing")
                    .isEqualTo(backlog - config.batchSize());
        });
    }

    /**
     * <b>One signal row per BATCH, not per row</b> (ADR 0010 §2.1). This is what keeps
     * {@code queue_signal} an index over the queue rather than a second copy of it: the table is sorted
     * by {@code due_at} on every poll, and it can only stay cheap to sort because it counts TENANTS. A
     * row-per-delivery signal would make a 40,000-message fan-out write 40,000 signal rows and turn the
     * discovery read into the scan it exists to replace.
     */
    @Test
    void aWholeBatchAnnouncesItselfWithOneSignalRow() throws Exception {
        withReceivers(() -> {
            seedBusyBacklog(3 * config.batchSize());

            assertThat(jdbc.queryForObject(
                    "select count(*) from platform.queue_signal where queue = 'webhook' and org_id = ?",
                    Integer.class, busyOrg))
                    .as("%d deliveries, one signal", 3 * config.batchSize())
                    .isEqualTo(1);
        });
    }

    // ---- fixture ----

    /**
     * Two real receivers on one server, so "delivered" means an HTTP request actually arrived rather
     * than a status column changed. A fixed pool because one drain POSTs a whole batch concurrently and
     * the default single-threaded dispatcher would serialise them into the send timeout.
     */
    private void withReceivers(ThrowingBody body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/busy", exchange -> {
            busyHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/quiet", exchange -> {
            quietHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            busySubscription = subscribe(busyOrg, "/busy");
            quietSubscription = subscribe(quietOrg, "/quiet");
            body.run();
        } finally {
            server.stop(0);
        }
    }

    private UUID subscribe(UUID orgId, String path) {
        return TenantContext.callAs(orgId, () -> subscriptions.create(orgId,
                "http://127.0.0.1:" + server.getAddress().getPort() + path, Set.of(EVENT)))
                .subscription().getId();
    }

    /**
     * One enqueue call for the whole backlog — the batch shape the signal is specified against — then
     * ages it two days. The ageing is what makes the ordering a FACT rather than a coincidence of
     * insertion order: these rows and this tenant's signal are strictly older than the quiet tenant's,
     * so a claim that ordered fleet-wide by age could only ever choose them.
     */
    private void seedBusyBacklog(int count) {
        String payload = WebhookPayload.of(EVENT, busyOrg, Instant.now()).toJson();
        List<NewWebhookDelivery> deliveries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            deliveries.add(new NewWebhookDelivery(busySubscription, busyOrg, EVENT, payload));
        }
        TenantContext.runAs(busyOrg, () -> {
            queue.enqueue(deliveries, config.maxAttempts());
            jdbc.update("update webhook_delivery set next_attempt_at = now() - interval '2 days' "
                    + "where org_id = ?", busyOrg);
            jdbc.update("update platform.queue_signal set due_at = now() - interval '2 days' "
                    + "where queue = 'webhook' and org_id = ?", busyOrg);
        });
    }

    /** One delivery, aged one day — younger than the backlog, older than anything a drain writes. */
    private void seedQuietDelivery() {
        String payload = WebhookPayload.of(EVENT, quietOrg, Instant.now()).toJson();
        TenantContext.runAs(quietOrg, () -> {
            queue.enqueue(List.of(new NewWebhookDelivery(quietSubscription, quietOrg, EVENT, payload)),
                    config.maxAttempts());
            jdbc.update("update webhook_delivery set next_attempt_at = now() - interval '1 day' "
                    + "where org_id = ?", quietOrg);
            jdbc.update("update platform.queue_signal set due_at = now() - interval '1 day' "
                    + "where queue = 'webhook' and org_id = ?", quietOrg);
        });
    }

    private List<String> statusOf(UUID orgId, UUID subscriptionId) {
        return TenantContext.callAs(orgId, () -> jdbc.queryForList(
                "select status from webhook_delivery where subscription_id = ?", String.class, subscriptionId));
    }

    @FunctionalInterface
    private interface ThrowingBody {
        void run() throws Exception;
    }
}
