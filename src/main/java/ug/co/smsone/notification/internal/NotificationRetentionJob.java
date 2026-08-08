package ug.co.smsone.notification.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.PLATFORM;

import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;

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
    private final io.micrometer.core.instrument.MeterRegistry meters;

    NotificationRetentionJob(NotificationDeliveryQueue queue, NotificationProperties properties, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.queue = queue;
        this.config = properties.delivery();
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * <b>AXIS: PLATFORM, and it is the tier rather than a leftover — this is the one job where "it
     * carries an org_id, so surely it is the tenant's" is wrong.</b> {@code notification_delivery} has
     * a genuinely nullable {@code org_id} (V41 added the column late) and is pure transport claimed by
     * a cluster-wide {@code SKIP LOCKED} sweep on a one-second poll; ADR 0010 §2 keeps it platform-tier
     * precisely because per-tenant queues would make an empty discovery pass cost more than the poll
     * interval. It is also the one retention job that consults NO {@code org_retention_override} — the
     * delivery log has a single cutoff for everyone — so unlike its webhook and exchange siblings there
     * is no tenant-tier read to open a second span for.
     *
     * <p><b>CURSOR: the delete.</b> Each batch removes terminal deliveries older than the cutoff, so an
     * interrupted run's remainder is the next run's input; there is no head to re-examine. Worth noting
     * against this job's own history: the unbounded in-loop DELETE it replaced had no cursor AND no
     * bound, ran on the poller thread on every instance, and swallowed its failures.
     *
     * <p><b>LEASE: PT30M, sized for {@link #MAX_BATCHES} × {@link #BATCH_SIZE} = 100,000 rows on ONE
     * schema, and that stays true past Phase 5 — this table does not fan out.</b> Which makes this the
     * simplest lease in the set and the one whose derivation is least likely to go stale. The number it
     * really depends on is send volume, not tenant count: if a day's terminal deliveries ever exceed
     * 100,000, the batch cap ends the pass with a backlog and the table grows monotonically, and the
     * fix is a bigger cap or a second nightly run rather than a longer lease.
     */
    @Scheduled(cron = "${app.scheduler.notification-retention-cron:0 25 4 * * *}")
    @SchedulerLock(name = "notification-delivery-retention", lockAtMostFor = "PT30M")
    @JobAxis(PLATFORM)
    public void purgeExpiredDeliveries() {
        Instant cutoff = clock.instant().minus(config.retention());
        // Declares the platform axis around the whole run: each batch commits on its own connection and
        // every one of those borrows reads the axis afresh, so one pin outside the loop covers them all.
        // ADR 0010 §3.4.
        // PLATFORM is the tier, not a placeholder: notification_delivery carries an org_id but stayed
        // platform-tier (§2) because it is transport claimed by a cluster-wide sweep. Unlike the webhook
        // and exchange retention jobs this one also consults no org_retention_override — the delivery
        // log has one cutoff for everyone — so there is no tenant-tier read to open a second span for.
        int total = TenantContext.callAsPlatform(() -> {
            int purged = 0;
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                int deleted = queue.purgeTerminalBatch(cutoff, BATCH_SIZE);
                purged += deleted;
                if (deleted < BATCH_SIZE) {
                    break;
                }
            }
            return purged;
        });
        ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "notification-delivery-retention", "notification_delivery", total);
        log.info("Purged {} terminal notification deliveries older than {}", total, config.retention());
    }
}
