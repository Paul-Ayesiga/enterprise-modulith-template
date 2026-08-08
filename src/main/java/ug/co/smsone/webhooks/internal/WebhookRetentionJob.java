package ug.co.smsone.webhooks.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.retention.RetentionOverrides;
import ug.co.smsone.shared.retention.RetentionPurges;
import ug.co.smsone.shared.retention.RetentionScope;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHomeSweep;

/**
 * Nightly retention for the delivery log — the machinery three in-repo claims described but nothing
 * ran. Lives here rather than in {@code scheduler} because it needs the module-internal
 * {@link WebhookDeliveryQueue} (the sanctioned exception in AGENTS §7). Bounded batches commit on
 * their own connections — never one long transaction against a live queue — and a failure aborts
 * the run loudly; ShedLock releases it for the next night.
 */
@Component
class WebhookRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetentionJob.class);
    private static final int BATCH_SIZE = 1000;
    private static final int MAX_BATCHES = 100; // bounds one run inside the ShedLock lease

    /**
     * How long the whole run may take, against the {@code PT30M} lease. Twenty-five is the five-minute
     * margin {@code SoftDeletePurgeJob}, {@code UsageExportJob} and {@code IdentityReconciliationJob}
     * all take, and it exists here for a reason this job did not previously have: <b>the run stopped
     * being one drain.</b> It is now (silos + 1) homes, each running a default drain plus one drain per
     * overridden org in that home, and nothing but a deadline bounds the product.
     *
     * <p>Whether twenty-five minutes covers a real fleet is arithmetic, not evidence — nobody has
     * measured a batch against a production {@code webhook_delivery}. What the deadline buys is that the
     * unmeasured case ends in a WARN and a resumed rotation instead of in a second replica purging the
     * same rows while the first still holds them.
     */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(25);

    /**
     * Which home this job is purging and where the rotation resumes — {@link TenantHomeSweep}, held as a
     * field because the position has to outlive a run. Without it a fleet too large for one pass would
     * re-purge the same head every night and never reach its tail, which for a retention promise is the
     * failure this job was written to fix in the first place.
     */
    private final TenantHomeSweep homes = new TenantHomeSweep("Webhook-delivery retention");

    private final WebhookDeliveryQueue queue;
    private final WebhookProperties properties;
    private final RetentionOverrides retentionOverrides;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final TenantFanOut fanOut;

    WebhookRetentionJob(WebhookDeliveryQueue queue, WebhookProperties properties,
            RetentionOverrides retentionOverrides, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters, TenantFanOut fanOut) {
        this.queue = queue;
        this.properties = properties;
        this.retentionOverrides = retentionOverrides;
        this.clock = clock;
        this.meters = meters;
        this.fanOut = fanOut;
    }

    /**
     * <b>AXIS: TENANT, one span</b> — see the comment in the body. Both {@code webhook_delivery} and the
     * {@code org_retention_override} rows {@link RetentionPurges} reads first are tenant-tier, so there
     * is no platform half to open a second span for.
     *
     * <p><b>Both passes run once per HOME since Phase 5.</b> A promoted tenant's deliveries are in its
     * own schema and its retention override is in that schema too, so a pooled-only run would have
     * purged neither — and would have logged a healthy row count while the promoted customer's delivery
     * log grew without bound. See {@link ug.co.smsone.shared.tenancy.TenantFanOut}.
     *
     * <p><b>CURSOR: two, and only one of them is positional.</b> Within a home it is the delete itself —
     * every batch removes terminal deliveries past their cutoff, so a pass stopped by
     * {@link #MAX_BATCHES} or by its slice of the deadline leaves the untouched remainder as the next
     * night's head, with nothing re-examined and nothing skipped. Across homes it is {@link #homes},
     * which is positional and has to be: a run cut at home 40 of 200 that restarted at home 1 the next
     * night would never reach the tail, and a retention promise that never reaches the tail is the
     * failure this job was written to fix in the first place.
     *
     * <p><b>LEASE: PT30M against {@link #RUN_DEADLINE}.</b> One home is a default drain of
     * {@link #MAX_BATCHES} × {@link #BATCH_SIZE} plus one drain per per-org override in that home; the
     * override pass is what makes the number fragile in a way the raw batch arithmetic hides, because it
     * is O(orgs with an override) rather than O(1) — which is why {@code RetentionPurges.Bounds} carries
     * a deadline and checks it between orgs as well as between batches. Nobody has measured a batch
     * against a real {@code webhook_delivery}; PT30M rests on 100 × 1,000 deletes being comfortable in
     * half an hour, which is true by a wide margin and is arithmetic rather than evidence.
     */
    @Scheduled(cron = "${app.scheduler.webhook-retention-cron:0 15 4 * * *}")
    @SchedulerLock(name = "webhook-delivery-retention", lockAtMostFor = "PT30M")
    @JobAxis(TENANT)
    public void purgeExpiredDeliveries() {
        // ONE tenant pin per HOME, and both things inside it need it for the same reason.
        // webhook_delivery is tenant-tier (ADR 0010 §2) and so is org_retention_override, which
        // RetentionPurges reads first to decide who is exempt from the default cutoff — on the platform
        // axis this job used to take, that read failed before a single row was ever considered.
        // Nothing here is platform's, so there is no second span to open, only more of this one.
        Instant now = clock.instant();
        int[] total = {0};
        TenantHomeSweep.Swept swept = homes.over(fanOut.fleet().homes(), clock, now.plus(RUN_DEADLINE),
                (home, deadline) -> total[0] += RetentionPurges.purge(retentionOverrides,
                        RetentionScope.WEBHOOK_DELIVERY, now, properties.retention(),
                        new RetentionPurges.Bounds(BATCH_SIZE, MAX_BATCHES, clock, deadline),
                        queue::purgeTerminalBatch, queue::purgeTerminalBatchForOrg));
        ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "webhook-delivery-retention", "webhook_delivery",
                total[0]);
        // One metric and one log line for the run, not one per home: "how many deliveries aged out
        // tonight" is one fact about this installation however many schemas it is spread over, and
        // splitting it would silently change what the dashboards read the day the first tenant is promoted.
        log.info("Purged {} terminal webhook deliveries across {} tenant home(s) (default retention {})",
                total[0], swept.visited(), properties.retention());
        swept.rethrowFirstFailure();
    }
}
