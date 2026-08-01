package ug.co.smsone.webhooks.internal;

import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    private final Clock clock;

    WebhookRetentionJob(WebhookDeliveryQueue queue, WebhookProperties properties, Clock clock) {
        this.queue = queue;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.scheduler.webhook-retention-cron:0 15 4 * * *}")
    @SchedulerLock(name = "webhook-delivery-retention", lockAtMostFor = "PT30M")
    public void purgeExpiredDeliveries() {
        Instant cutoff = clock.instant().minus(properties.retention());
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = queue.purgeTerminalBatch(cutoff, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        log.info("Purged {} terminal webhook deliveries older than {}", total, properties.retention());
    }
}
