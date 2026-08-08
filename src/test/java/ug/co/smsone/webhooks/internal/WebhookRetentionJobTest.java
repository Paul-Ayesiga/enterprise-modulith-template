package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The retention that three in-repo claims described before anything ran it. Terminal rows past the
 * window go; queued work and everything inside the window is never retention's business.
 *
 * <p>{@code webhook_subscription} and {@code webhook_delivery} are TENANT-tier (ADR 0010 §2), so the
 * fixtures and the assertions below declare the org's axis; the harness pins PLATFORM, where neither
 * table resolves. {@code job.purgeExpiredDeliveries()} is deliberately left un-pinned — declaring its
 * own axis is the job's obligation, and running it from a PLATFORM-pinned thread is what makes that
 * obligation falsifiable here rather than borrowed from the harness.
 */
class WebhookRetentionJobTest extends AbstractIntegrationTest {

    @Autowired
    private WebhookSubscriptionService subscriptions;

    @Autowired
    private WebhookRetentionJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private double purged() {
        io.micrometer.core.instrument.Counter counter = meters.find("smsone.purge.deleted")
                .tag("job", "webhook-delivery-retention").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void purgesOldTerminalRowsAndNothingElse() {
        double purgedBefore = purged();
        UUID orgId = UUID.randomUUID();
        WebhookSubscription subscription = TenantContext.callAs(orgId, () -> subscriptions.create(orgId,
                "http://127.0.0.1:1/unreachable", Set.of("org.member.added"))).subscription();
        UUID oldDelivered = insert(subscription.getId(), orgId, "DELIVERED", "40 days");
        UUID oldFailed = insert(subscription.getId(), orgId, "FAILED", "40 days");
        UUID oldPending = insert(subscription.getId(), orgId, "PENDING", "40 days");
        UUID youngDelivered = insert(subscription.getId(), orgId, "DELIVERED", "1 day");

        runRetention();

        assertThat(exists(orgId, oldDelivered)).as("DELIVERED past retention goes").isFalse();
        assertThat(exists(orgId, oldFailed)).as("dead-letters age out too — the log is not an archive").isFalse();
        assertThat(exists(orgId, oldPending)).as("queued work is never retention's business").isTrue();
        assertThat(exists(orgId, youngDelivered)).as("inside the window stays").isTrue();
        assertThat(purged()).as("the purge reports its work — silence is the alertable failure")
                .isEqualTo(purgedBefore + 2);
    }

    /**
     * <b>Releases the lease before every run, or this test observes nothing at all.</b>
     * {@code @SchedulerLock} is around-advice on the bean's proxy, so it fires on a direct call exactly
     * as it does on the cron one — and {@code SchedulingConfig}'s {@code defaultLockAtLeastFor = PT30S}
     * means a completed run holds {@code webhook-delivery-retention} for thirty seconds AFTER it
     * returns. A second acquisition inside that window is refused and the method body is skipped
     * <em>in silence</em>: no exception, no log line, and every "the aged row is gone" assertion in the
     * test above fails while the purge itself is perfectly healthy.
     *
     * <p><b>The window here is CROSS-CLASS, which is why the release cannot live in one test.</b> One
     * Postgres container and one {@code platform.shedlock} row serve the whole suite, and
     * {@code WebhookRetentionFanOutTest} runs this same job seconds before this class starts — so
     * whichever of the two runs first leases the job away from the other. Every other job test in the
     * suite already does this ({@code ExchangeRetentionJobTest}, {@code TrialExpiryFanOutTest},
     * {@code SoftDeletePurgeFanOutTest}, {@code SupportDeskFanOutTest}); the two webhook-retention
     * classes were the only ones that did not.
     *
     * <p>{@code shedlock} is platform-tier and named explicitly (ADR 0010 §2), which the harness's
     * PLATFORM pin would resolve anyway — the qualifier is what makes the read independent of it.
     */
    private void runRetention() {
        jdbc.update("update platform.shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "webhook-delivery-retention");
        job.purgeExpiredDeliveries();
    }

    private UUID insert(UUID subscriptionId, UUID orgId, String status, String age) {
        UUID id = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update(
                "insert into webhook_delivery (id, subscription_id, org_id, event_type, payload, "
                        + "status, attempts, max_attempts, next_attempt_at, created_at) "
                        + "values (?, ?, ?, 'org.member.added', '{}', ?, 1, 5, now(), now() - interval '" + age + "')",
                id, subscriptionId, orgId, status));
        return id;
    }

    /**
     * {@code orgId} is a parameter and not a field so the read names the tenant whose row it is —
     * the same discipline the delivery test keeps, and the one that stops a two-tenant version of this
     * test from quietly reading one org's rows on the other's axis.
     */
    private boolean exists(UUID orgId, UUID id) {
        return Boolean.TRUE.equals(TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select exists(select 1 from webhook_delivery where id = ?)", Boolean.class, id)));
    }
}
