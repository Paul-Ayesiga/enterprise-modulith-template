package ug.co.smsone.exchange.internal;

import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.metrics.PurgeMetrics;

/**
 * The retention job the V24 terminal index was always waiting for: terminal jobs past
 * {@code app.exchange.retention} purge nightly, batched, ShedLock-guarded — the job log is a log,
 * not an archive. Row errors cascade with their job; the ARTIFACTS (source, report, result) are
 * deliberately untouched — they are documents now, with the document lifecycle, and a tenant's
 * report may matter long after the job row stopped doing so. Lives here rather than in
 * {@code scheduler} for the sanctioned reason (AGENTS §7): it needs this module's store.
 */
@Component
class ExchangeRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRetentionJob.class);
    private static final int BATCH_SIZE = 1000;
    private static final int MAX_BATCHES = 100; // bounds one run inside the ShedLock lease

    private final ExchangeJobStore store;
    private final ExchangeProperties config;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    ExchangeRetentionJob(ExchangeJobStore store, ExchangeProperties config, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(cron = "${app.scheduler.exchange-retention-cron:0 45 4 * * *}")
    @SchedulerLock(name = "exchange-job-retention", lockAtMostFor = "PT30M")
    public void purgeExpiredJobs() {
        Instant cutoff = clock.instant().minus(config.retention());
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = store.purgeTerminalBatch(cutoff, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        PurgeMetrics.purged(meters, "exchange-job-retention", "exchange_job", total);
        log.info("Purged {} terminal exchange jobs older than {}", total, config.retention());
    }
}
