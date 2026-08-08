package ug.co.smsone.exchange.internal;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.TenantContext;

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
    private final ExchangeMetrics metrics;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final java.time.Clock clock;

    private volatile boolean running;
    private volatile Thread poller;

    ExchangeWorker(ExchangeJobStore store, ImportRunner imports, ExportRunner exports,
            ExchangeProperties config, ExchangeMetrics metrics,
            org.springframework.context.ApplicationEventPublisher events,
            org.springframework.transaction.support.TransactionTemplate transactions,
            java.time.Clock clock) {
        this.store = store;
        this.imports = imports;
        this.exports = exports;
        this.config = config;
        this.metrics = metrics;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
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

    /**
     * Claim and fully run one job; returns how many were run (0 or 1).
     *
     * <p>Declares the platform axis (ADR 0010 §3.4): the poller is a thread this class starts itself,
     * so no {@code TaskDecorator} covers it, and tests drive this method directly with no request
     * behind them either. The pin wraps the claim, the run and {@link #recordOutcome} — the last of
     * which opens its own transaction, which is the only order that works: the transaction chooses its
     * connection, and the axis has to already be set when it does.
     *
     * <p><b>PHASE 2 SPLITS THIS PIN IN TWO, and the seam is already visible below.</b> The claim is a
     * cross-tenant {@code SKIP LOCKED} scan over one queue and stays platform work (or becomes a scan
     * per tenant). Everything from {@code MDC.put("org_id", …)} onwards is one tenant's work — the
     * handler reads and writes THAT org's rows — so it becomes
     * {@code TenantContext.runAs(job.orgId(), …)}. The MDC line marks the boundary today; it is not a
     * coincidence that the value it puts there is exactly the axis the pin will take.
     */
    public int drainOnce() {
        return TenantContext.callAsPlatform(this::claimAndRunOne);
    }

    private int claimAndRunOne() {
        Optional<ExchangeJob> claimed = store.claimOne(config.staleLock());
        if (claimed.isEmpty()) {
            return 0;
        }
        ExchangeJob job = claimed.get();
        if (job.attempts() > config.maxAttempts()) {
            // Reclaim-loop circuit breaker: attempts normally cap inside failOrRetry, but a job
            // that keeps LOSING its claim (never reaching an exception) would otherwise burn claim
            // generations forever. Above the cap it dies loudly instead.
            store.markTerminal(job.id(), job.attempts(), ExchangeJob.FAILED, null, null,
                    "The job was reclaimed repeatedly without completing and gave up after "
                            + job.attempts() + " attempts. Quote job id " + job.id() + " to support.");
            recordOutcome(job);
            return 1;
        }
        // MDC, not method arguments: every log line the run produces — handler code included —
        // carries the tenant and the job without any layer having to pass them along.
        MDC.put("org_id", String.valueOf(job.orgId()));
        MDC.put("exchange_job_id", job.id().toString());
        MDC.put("exchange_handler", job.handler());
        try {
            if (ExchangeJob.EXPORT.equals(job.jobType())) {
                exports.run(job);
            } else {
                imports.run(job);
            }
            // One read, one call site: the runners already wrote the terminal status, so counting
            // from the row can never disagree with it (a released-for-retry job is not an outcome
            // yet). Inside the MDC scope, so a failure here logs with its job attribution.
            recordOutcome(job);
        } finally {
            MDC.remove("org_id");
            MDC.remove("exchange_job_id");
            MDC.remove("exchange_handler");
        }
        return 1;
    }

    /**
     * Terminal bookkeeping from the ROW, not the runner's memory: the counter and the
     * {@link ug.co.smsone.exchange.JobCompleted} event both repeat what the fenced terminal write
     * said, so neither can disagree with the API. Published inside a small transaction so the
     * Modulith registry row commits with it (after-commit delivery to the async consumers).
     */
    private void recordOutcome(ExchangeJob claimed) {
        store.find(claimed.id(), claimed.orgId()).ifPresent(after -> {
            metrics.jobFinished(after);
            if (after.terminal()) {
                transactions.executeWithoutResult(tx -> events.publishEvent(
                        new ug.co.smsone.exchange.JobCompleted(after.id(), after.orgId(),
                                after.requesterPersonId(), after.handler(), after.jobType(), after.status(),
                                after.processed(), after.failed(), clock.instant())));
            }
        });
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
