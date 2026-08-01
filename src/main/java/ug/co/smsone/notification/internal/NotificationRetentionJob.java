package ug.co.smsone.notification.internal;

import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly retention for {@code notification_delivery}, replacing the worker's in-loop purge — that
 * ran unlocked on every instance, as one unbounded DELETE on the poller thread, with failures
 * swallowed (all three against AGENTS §7). Lives here rather than in {@code scheduler} because it
 * needs the module-internal {@link NotificationDeliveryQueue} (the sanctioned exception). Bounded
 * batches; a failure aborts the run loudly and ShedLock releases it for the next night.
 */
@Component
class NotificationRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetentionJob.class);
    private static final int BATCH_SIZE = 1000;
    private static final int MAX_BATCHES = 100; // bounds one run inside the ShedLock lease

    private final NotificationDeliveryQueue queue;
    private final NotificationProperties.Delivery config;
    private final Clock clock;

    NotificationRetentionJob(NotificationDeliveryQueue queue, NotificationProperties properties, Clock clock) {
        this.queue = queue;
        this.config = properties.delivery();
        this.clock = clock;
    }

    @Scheduled(cron = "${app.scheduler.notification-retention-cron:0 25 4 * * *}")
    @SchedulerLock(name = "notification-delivery-retention", lockAtMostFor = "PT30M")
    public void purgeExpiredDeliveries() {
        Instant cutoff = clock.instant().minus(config.retention());
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = queue.purgeTerminalBatch(cutoff, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        log.info("Purged {} terminal notification deliveries older than {}", total, config.retention());
    }
}
