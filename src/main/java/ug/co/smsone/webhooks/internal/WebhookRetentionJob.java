package ug.co.smsone.webhooks.internal;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.retention.RetentionOverrides;
import ug.co.smsone.shared.retention.RetentionPurges;
import ug.co.smsone.shared.retention.RetentionScope;

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
        int total = RetentionPurges.purge(retentionOverrides, RetentionScope.WEBHOOK_DELIVERY,
                clock.instant(), properties.retention(), BATCH_SIZE, MAX_BATCHES,
                queue::purgeTerminalBatch, queue::purgeTerminalBatchForOrg);
        ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "webhook-delivery-retention", "webhook_delivery", total);
        log.info("Purged {} terminal webhook deliveries (default retention {})", total, properties.retention());
    }
}
