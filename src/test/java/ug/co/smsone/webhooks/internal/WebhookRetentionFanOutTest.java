package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>Delivery retention with one organization in its own schema and the rest pooled</b>
 * (ADR 0010 §7 Phase 5).
 *
 * <p>{@code WebhookRetentionJobTest} owns what the purge does within one schema and proves it against
 * {@code tenant_pool}; it would have gone on passing on the night a promoted tenant's delivery log
 * started growing without bound. Two things had to move for this to work and both are tenant-tier:
 * {@code webhook_delivery} itself, and the {@code org_retention_override} rows
 * {@code RetentionPurges} reads FIRST to decide who is exempt from the default cutoff — which is why
 * {@code RetentionOverridesImpl.daysByScope} stopped declaring the pooled axis and now answers for
 * whichever home the fan-out has pinned.
 */
class WebhookRetentionFanOutTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private WebhookSubscriptionService subscriptions;

    @Autowired
    private WebhookRetentionJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purgesAgedDeliveriesInASiloAndInThePoolInTheSameRun() {
        UUID pooled = organization();
        UUID siloed = organization();
        silos.place(siloed);

        UUID pooledAged = insertAgedDelivery(pooled);
        UUID siloedAged = insertAgedDelivery(siloed);
        UUID siloedFresh = insertDelivery(siloed, "DELIVERED", "1 day");
        // Schema-qualified, before the job runs. Seeding and reading both resolve through
        // TenantContext, so without this probe every assertion below would pass just as happily with
        // the row in tenant_pool and t_<hex> empty from creation to drop.
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "webhook_delivery", "id", siloedAged);

        runRetention();

        assertThat(exists(pooled, pooledAged)).as("the pooled tenant's aged delivery goes").isFalse();
        assertThat(exists(siloed, siloedAged))
                .as("and so does the siloed tenant's. Before the fan-out its delivery log was never "
                        + "trimmed again, and the job logged a healthy count every night")
                .isFalse();
        assertThat(exists(siloed, siloedFresh))
                .as("retention is still a window in a silo — a fan-out that reached the schema but "
                        + "carried the wrong cutoff would be the unrecoverable mistake")
                .isTrue();
    }

    /**
     * A per-org override is itself tenant-tier, so in a silo it can only be read on that silo's axis.
     * A fan-out that reached the silo's deliveries while still reading the POOL's overrides would purge
     * this tenant at the platform default — quietly overriding a contract with the customer, in the
     * direction that deletes data early.
     *
     * <p><b>Two rows, because one cannot tell the two failures apart.</b> The obvious version of this
     * test seeds a forty-day delivery and asserts it SURVIVES. That assertion holds when the override
     * was honoured — and equally when the sweep never reached this home at all, or when the job never
     * ran. It was green through the entire silo-per-org migration while proving none of those things:
     * for one whole suite run the job really was being skipped in silence by a ShedLock lease it had
     * not released, and this test passed anyway. A test whose only assertion is that nothing happened
     * cannot distinguish correctness from inaction.
     *
     * <p>So the survivor is paired with a row that must NOT survive: one aged past even this tenant's
     * ten-year window. Now the two failure directions are separated and each has its own assertion —
     * the sweep must have visited this schema (or the eleven-year row is still there), and it must have
     * carried THIS schema's cutoff rather than the pool's (or the forty-day row is gone).
     */
    @Test
    void aSiloedTenantsOwnRetentionOverrideIsHonouredRatherThanThePlatformDefault() {
        UUID siloed = organization();
        silos.place(siloed);
        UUID override = UUID.randomUUID();
        TenantContext.runAs(siloed, () -> jdbc.update("""
                insert into org_retention_override (id, org_id, scope, retention_days, version, created_at)
                values (?, ?, 'WEBHOOK_DELIVERY', 3650, 0, now())
                """, override, siloed));
        UUID aged = insertAgedDelivery(siloed);
        // Past 3650 days, so the override cannot save it and only an unvisited schema can.
        UUID pastEvenTheOverride = insertDelivery(siloed, "DELIVERED", "4015 days");
        // BOTH have to be physically in the silo for this test to mean what it says: the delivery the
        // sweep must not purge, and the override that is the only reason it must not.
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "org_retention_override", "id", override);
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "webhook_delivery", "id", aged);
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "webhook_delivery", "id", pastEvenTheOverride);

        runRetention();

        assertThat(exists(siloed, pastEvenTheOverride))
                .as("eleven years is past this tenant's own ten-year window, so this row must be gone — "
                        + "and it can only be gone if the sweep actually visited this silo. This is the "
                        + "assertion that makes the next one mean something: without it, a sweep that "
                        + "never reached the schema is indistinguishable from one that honoured the "
                        + "override")
                .isFalse();
        assertThat(exists(siloed, aged))
                .as("forty days is past the platform default and nowhere near this tenant's ten years, "
                        + "so the row must survive — which it only can if the override was read from the "
                        + "silo rather than from the pool")
                .isTrue();
        TenantContext.runAs(siloed, () ->
                jdbc.update("delete from org_retention_override where org_id = ?", siloed));
    }

    /**
     * <b>Releases the lease before every run, or neither test in this class observes anything.</b>
     * {@code @SchedulerLock} is around-advice on the bean's proxy and fires on a direct call exactly as
     * it does on the cron one; {@code SchedulingConfig}'s {@code defaultLockAtLeastFor = PT30S} then
     * holds {@code webhook-delivery-retention} for thirty seconds AFTER a run returns, and a second
     * acquisition inside that window is refused with the body skipped <em>in silence</em> — no
     * exception, no log line, no metric.
     *
     * <p><b>Both failure directions are here, and only one of them is loud.</b> This class's two tests
     * run half a second apart, so without this the second one's purge never happens: the aged-delivery test
     * fails on an assertion that names the pool and points at the fan-out, which is the wrong place
     * entirely. The override test is worse — it asserts a row SURVIVES, which is exactly what a job
     * that never ran leaves behind, so it goes green while proving nothing. And the window reaches past
     * this class: one {@code platform.shedlock} row serves the whole suite, and
     * {@code WebhookRetentionJobTest} calls the same job a second later.
     *
     * <p>{@code shedlock} is platform-tier and named explicitly (ADR 0010 §2) rather than left to the
     * harness's PLATFORM pin, which is the same shape {@code SupportDeskFanOutTest.runEscalation} and
     * {@code TrialExpiryFanOutTest.runExpiry} already use.
     */
    private void runRetention() {
        jdbc.update("update platform.shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "webhook-delivery-retention");
        job.purgeExpiredDeliveries();
    }

    private UUID organization() {
        return EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "wh-fanout-" + UUID.randomUUID());
    }

    private UUID insertAgedDelivery(UUID orgId) {
        return insertDelivery(orgId, "DELIVERED", "40 days");
    }

    /**
     * The subscription and the delivery are both TENANT-tier and both written on the org's own axis, so
     * for a placed org the whole pair lands in {@code t_<hex>} — including the signing secret, which is
     * the fact that put {@code webhook_delivery} on this side of the boundary in the first place
     * (ADR 0010 §2.1).
     */
    private UUID insertDelivery(UUID orgId, String status, String age) {
        WebhookSubscription subscription = TenantContext.callAs(orgId, () -> subscriptions.create(orgId,
                "http://127.0.0.1:1/unreachable", Set.of("org.member.added")).subscription());
        UUID id = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update(
                "insert into webhook_delivery (id, subscription_id, org_id, event_type, payload, "
                        + "status, attempts, max_attempts, next_attempt_at, created_at) "
                        + "values (?, ?, ?, 'org.member.added', '{}', ?, 1, 5, now(), "
                        + "now() - interval '" + age + "')",
                id, subscription.getId(), orgId, status));
        return id;
    }

    private boolean exists(UUID orgId, UUID id) {
        return Boolean.TRUE.equals(TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select exists(select 1 from webhook_delivery where id = ?)", Boolean.class, id)));
    }
}
