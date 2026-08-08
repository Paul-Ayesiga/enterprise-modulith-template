package ug.co.smsone.webhooks.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import java.time.Clock;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.retention.RetentionOverrides;
import ug.co.smsone.shared.retention.RetentionPurges;
import ug.co.smsone.shared.retention.RetentionScope;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;

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
     * The tenant axis both halves of this run need. Names no organization deliberately: an org in no
     * {@code organization} row can only ever resolve to the shared {@code tenant_pool}, so this IS the
     * pooled schema's axis — the same constant and the same reasoning as
     * {@link WebhookSecretEncryptionMigrator} and {@code MappedSchemaValidator}.
     *
     * <p>PHASE 5 makes this a loop over {@code platform.tenant_placement}: "every org's deliveries"
     * stops being one schema, and both passes below (the default sweep and the per-override one) run
     * once per home.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

    private final WebhookDeliveryQueue queue;
    private final WebhookProperties properties;
    private final RetentionOverrides retentionOverrides;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    WebhookRetentionJob(WebhookDeliveryQueue queue, WebhookProperties properties,
            RetentionOverrides retentionOverrides, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.queue = queue;
        this.properties = properties;
        this.retentionOverrides = retentionOverrides;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * <b>AXIS: TENANT, one span</b> — see the comment in the body. Both {@code webhook_delivery} and the
     * {@code org_retention_override} rows {@link RetentionPurges} reads first are tenant-tier, so there
     * is no platform half to open a second span for.
     *
     * <p><b>CURSOR: the delete.</b> Every batch removes terminal deliveries past their cutoff, so a pass
     * stopped by {@link #MAX_BATCHES} or by an expired lease leaves the untouched remainder as the next
     * night's head — nothing is re-examined and nothing is skipped, and there is no position worth
     * holding in memory.
     *
     * <p><b>LEASE: PT30M, sized for one default drain of {@link #MAX_BATCHES} × {@link #BATCH_SIZE}
     * plus one drain per per-org override, all in ONE schema.</b> The per-override pass is what makes
     * this number fragile in a way the raw batch arithmetic hides: it is O(orgs with an override), not
     * O(1), so the lease is already sized by a number that grows with the customer base rather than
     * with traffic. Nobody has measured a batch against a real {@code webhook_delivery}; PT30M rests on
     * 100 × 1,000 deletes being comfortable in half an hour, which is true by a wide margin and is
     * arithmetic rather than evidence. ADR 0010 Phase 5 makes both passes run once per home, which is
     * the point at which this must be re-derived WITH a resumable per-home cursor — a fan-out cut at
     * home 40 of 200 that restarts at home 1 the next night never reaches the tail, and a retention
     * promise that never reaches the tail is the failure this job was written to fix in the first
     * place.
     */
    @Scheduled(cron = "${app.scheduler.webhook-retention-cron:0 15 4 * * *}")
    @SchedulerLock(name = "webhook-delivery-retention", lockAtMostFor = "PT30M")
    @JobAxis(TENANT)
    public void purgeExpiredDeliveries() {
        // ONE tenant pin over the whole run, and both things inside it need it for the same reason.
        // webhook_delivery is tenant-tier (ADR 0010 §2) and so is org_retention_override, which
        // RetentionPurges reads first to decide who is exempt from the default cutoff — on the platform
        // axis this job used to take, that read failed before a single row was ever considered.
        // Nothing here is platform's, so there is no second span to open.
        int total = TenantContext.callAs(POOLED_TENANT, () -> RetentionPurges.purge(retentionOverrides,
                RetentionScope.WEBHOOK_DELIVERY, clock.instant(), properties.retention(), BATCH_SIZE, MAX_BATCHES,
                queue::purgeTerminalBatch, queue::purgeTerminalBatchForOrg));
        ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "webhook-delivery-retention", "webhook_delivery", total);
        log.info("Purged {} terminal webhook deliveries (default retention {})", total, properties.retention());
    }
}
