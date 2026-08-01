package ug.co.smsone.exchange.internal;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Polls the job queue and runs claimed jobs — ONE at a time per instance, deliberately: a job is
 * internally a marathon of batches, so parallelism comes from instances sharing the queue via
 * {@code SKIP LOCKED}, not from threads competing inside one. A stop mid-job is safe by
 * construction: the row stays claimed, goes stale, and another instance resumes it from the last
 * committed offset. In tests the poller is disabled ({@code app.exchange.worker-auto-start=false})
 * and {@link #drainOnce()} is driven directly.
 */
@Component
class ExchangeWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ExchangeWorker.class);

    private final ExchangeJobStore store;
    private final ImportRunner imports;
    private final ExportRunner exports;
    private final ExchangeProperties config;

    private volatile boolean running;
    private volatile Thread poller;

    ExchangeWorker(ExchangeJobStore store, ImportRunner imports, ExportRunner exports,
            ExchangeProperties config) {
        this.store = store;
        this.imports = imports;
        this.exports = exports;
        this.config = config;
    }

    @Override
    public void start() {
        running = true;
        if (config.workerAutoStart()) {
            poller = Thread.ofVirtual().name("exchange-job-poller").start(this::runLoop);
            log.info("Exchange worker started (batchSize={}, poll={}, staleLock={})",
                    config.batchSize(), config.pollInterval(), config.staleLock());
        }
    }

    @Override
    public void stop() {
        running = false;
        Thread p = poller;
        if (p != null) {
            p.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100; // stop early, before the DataSource closes
    }

    private void runLoop() {
        while (running) {
            try {
                if (drainOnce() == 0) {
                    Thread.sleep(config.pollInterval().toMillis());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                if (running) {
                    log.warn("Exchange poll failed: {}", ex.toString());
                    sleepQuietly(config.pollInterval().toMillis());
                }
            }
        }
    }

    /** Claim and fully run one job; returns how many were run (0 or 1). */
    public int drainOnce() {
        Optional<ExchangeJob> claimed = store.claimOne(config.staleLock());
        if (claimed.isEmpty()) {
            return 0;
        }
        ExchangeJob job = claimed.get();
        if (ExchangeJob.EXPORT.equals(job.jobType())) {
            exports.run(job);
        } else {
            imports.run(job);
        }
        return 1;
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
