package ug.co.smsone.scheduler.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.idempotency.IdempotencyProperties;
import ug.co.smsone.shared.idempotency.IdempotencyStore;
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

    @Scheduled(cron = "${app.scheduler.idempotency-purge-cron:0 30 3 * * *}")
    @SchedulerLock(name = "idempotency-key-purge", lockAtMostFor = "PT30M")
    public void purgeExpiredKeys() {
        // Declares the platform axis: no request thread, so nothing else would. ADR 0010 §3.4.
        int purged = TenantContext.callAsPlatform(() -> store.purgeOlderThan(properties.retention()));
        ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "idempotency-key-purge", "idempotency_key", purged);
        log.info("Purged {} idempotency keys older than {}", purged, properties.retention());
    }
}
