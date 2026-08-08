package ug.co.smsone.webhooks.internal;

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

    @Scheduled(cron = "${app.scheduler.webhook-retention-cron:0 15 4 * * *}")
    @SchedulerLock(name = "webhook-delivery-retention", lockAtMostFor = "PT30M")
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
