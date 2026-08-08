package ug.co.smsone.scheduler.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.PLATFORM;

import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.events.EventInbox;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;

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

    /**
     * <b>AXIS: PLATFORM.</b> {@code event_inbox} is the consuming half of this deployment's own
     * outbox/inbox seam — created by {@code db/migration/platform/V7}, one copy, no {@code org_id} — so
     * it is platform-tier and stays one span past Phase 5.
     *
     * <p><b>CURSOR: the delete itself, and the batch loop needs no more than that.</b>
     * {@link EventInbox#purgeProcessedBatch} takes the oldest {@link #BATCH_SIZE} processed rows each
     * time, so every batch permanently shrinks the candidate set: a run stopped by
     * {@link #MAX_BATCHES} — or by a lease that expired under it — leaves the untouched tail as the next
     * run's head. There is no in-memory position to keep, because the position is the table.
     *
     * <p><b>LEASE: PT30M, sized for {@link #MAX_BATCHES} × {@link #BATCH_SIZE} = 100,000 rows on ONE
     * schema, and that bound is real rather than nominal</b> — unlike the two sibling purges, this one
     * cannot issue a statement it is unable to stop. 100k deletes in a half hour is 55 rows a second,
     * so the lease has orders of magnitude of headroom on any hardware this runs on; it is not a
     * measured number and does not need to be, because the batch cap is what actually ends the pass.
     * Neither number scales with tenant count — {@code platform} is one schema forever — so Phase 5
     * leaves both alone.
     */
    @Scheduled(cron = "${app.scheduler.event-inbox-purge-cron:0 45 3 * * *}")
    @SchedulerLock(name = "event-inbox-purge", lockAtMostFor = "PT30M")
    @JobAxis(PLATFORM)
    public void purgeExpiredInboxRows() {
        // Declares the platform axis: nothing pins one off a request thread, and `event_inbox` is
        // platform-tier bookkeeping. ADR 0010 §3.4.
        TenantContext.runAsPlatform(this::purge);
    }

    private void purge() {
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
