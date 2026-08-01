package ug.co.smsone.scheduler.internal;

import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.events.EventInbox;

/**
 * Sweeps inbox dedup rows past retention — previously the one durable table with no cleanup at all.
 * Bounded batches commit on their own connections; a failure aborts the run loudly (single-kind
 * work, AGENTS §7) and ShedLock releases it for the next night.
 */
@Component
class EventInboxPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(EventInboxPurgeJob.class);
    private static final int BATCH_SIZE = 1000;
    private static final int MAX_BATCHES = 100; // bounds one run inside the ShedLock lease

    private final EventInbox inbox;
    private final SchedulerRetentionProperties properties;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    EventInboxPurgeJob(EventInbox inbox, SchedulerRetentionProperties properties, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.inbox = inbox;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(cron = "${app.scheduler.event-inbox-purge-cron:0 45 3 * * *}")
    @SchedulerLock(name = "event-inbox-purge", lockAtMostFor = "PT30M")
    public void purgeExpiredInboxRows() {
        Instant cutoff = clock.instant().minus(properties.eventInboxRetention());
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = inbox.purgeProcessedBatch(cutoff, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "event-inbox-purge", "event_inbox", total);
        log.info("Purged {} event-inbox rows older than {}", total, properties.eventInboxRetention());
    }
}
