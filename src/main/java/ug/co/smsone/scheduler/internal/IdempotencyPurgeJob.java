package ug.co.smsone.scheduler.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.idempotency.IdempotencyProperties;
import ug.co.smsone.shared.idempotency.IdempotencyStore;

/** Sweeps expired idempotency keys; replays are only guaranteed within the retention window. */
@Component
class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;

    IdempotencyPurgeJob(IdempotencyStore store, IdempotencyProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.scheduler.idempotency-purge-cron:0 30 3 * * *}")
    @SchedulerLock(name = "idempotency-key-purge", lockAtMostFor = "PT30M")
    public void purgeExpiredKeys() {
        int purged = store.purgeOlderThan(properties.retention());
        log.info("Purged {} idempotency keys older than {}", purged, properties.retention());
    }
}
