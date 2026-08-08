package ug.co.smsone.scheduler.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.PLATFORM;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.idempotency.IdempotencyProperties;
import ug.co.smsone.shared.idempotency.IdempotencyStore;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;

/** Sweeps expired idempotency keys; replays are only guaranteed within the retention window. */
@Component
class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    IdempotencyPurgeJob(IdempotencyStore store, IdempotencyProperties properties,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.store = store;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * <b>AXIS: PLATFORM.</b> {@code idempotency_key} is keyed on {@code (principal, idem_key)} and
     * carries no {@code org_id} at all — the principal may be an API key, an operator or a machine, and
     * a replay window is a property of this deployment's HTTP surface rather than of a tenant. V5
     * creates it in {@code db/migration/platform} and Phase 5 gives it no second home.
     *
     * <p><b>CURSOR: the delete, again</b> — {@link IdempotencyStore#purgeOlderThan} drains
     * oldest-{@code created_at}-first in bounded batches, so an interrupted run's remainder is the next
     * run's input and nothing is re-examined. Worth stating explicitly because the consequence of
     * being wrong here is invisible: an unswept key is not a stuck row, it is a replay that keeps
     * answering from cache past the window the API promises.
     *
     * <p><b>LEASE: PT30M, bounded by {@link IdempotencyStore}'s own batch cap and not by this
     * annotation.</b> The honest reading is that PT30M is a ceiling nothing is expected to approach —
     * the store's loop ends the pass long before it — and that it is sized for ONE schema whose churn
     * tracks request volume, not tenant count. If a run ever hits the lease, raising it is the wrong
     * fix: it means the retention window is producing more keys per night than a night can delete, and
     * the batch cap in the store is where that gets answered.
     */
    @Scheduled(cron = "${app.scheduler.idempotency-purge-cron:0 30 3 * * *}")
    @SchedulerLock(name = "idempotency-key-purge", lockAtMostFor = "PT30M")
    @JobAxis(PLATFORM)
    public void purgeExpiredKeys() {
        // Declares the platform axis: no request thread, so nothing else would. ADR 0010 §3.4.
        int purged = TenantContext.callAsPlatform(() -> store.purgeOlderThan(properties.retention()));
        ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "idempotency-key-purge", "idempotency_key", purged);
        log.info("Purged {} idempotency keys older than {}", purged, properties.retention());
    }
}
