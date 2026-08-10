package ug.co.smsone.exchange.internal;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.queue.QueueSignals;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHome;

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

    /**
     * How many scopes one drain will look at and find nothing in before giving up for this poll.
     *
     * <p>A signal can outlive the work it announced — the last job of a scope is cancelled, retention
     * purges a terminal row — and ADR 0010 §2.1 makes that explicitly harmless: the worker that finds
     * nothing is the thing that cleans it up. The bound stops that cleanup from turning one poll into
     * the sweep this whole mechanism replaced. Each probe leaves its scope either deleted or not due
     * again, so the loop never revisits one and whatever it misses this poll it reaches on the next.
     */
    private static final int MAX_EMPTY_PROBES = 32;

    private final ExchangeJobStore store;
    private final QueueSignals signals;
    private final ImportRunner imports;
    private final ExportRunner exports;
    private final ExchangeProperties config;
    private final ExchangeMetrics metrics;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final java.time.Clock clock;
    private final TenantFanOut fanOut;

    private volatile boolean running;
    private volatile Thread poller;

    ExchangeWorker(ExchangeJobStore store, QueueSignals signals, ImportRunner imports,
            ExportRunner exports, ExchangeProperties config, ExchangeMetrics metrics,
            org.springframework.context.ApplicationEventPublisher events,
            org.springframework.transaction.support.TransactionTemplate transactions,
            java.time.Clock clock, TenantFanOut fanOut) {
        this.store = store;
        this.signals = signals;
        this.imports = imports;
        this.exports = exports;
        this.config = config;
        this.metrics = metrics;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        this.fanOut = fanOut;
    }

    /** ADR 0011 §5's residue sweep interval — {@code WebhookDeliveryWorker.RECONCILE_EVERY}'s reasoning. */
    private static final java.time.Duration RECONCILE_EVERY = java.time.Duration.ofMinutes(5);

    /** When the next residue pass may run. Per instance and idempotent; see the webhook worker's note. */
    private volatile java.time.Instant reconcileAfter = java.time.Instant.MIN;

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
     * <p><b>PHASE 2 SPLIT THIS PIN IN TWO, exactly where the seam was drawn.</b> {@code exchange_job}
     * is a split table (ADR 0010 §2 row 10), so the claim is not one cross-tenant {@code SKIP LOCKED}
     * scan: it is schema-qualified, because work nobody has claimed yet belongs to no axis. Everything
     * from {@code MDC.put("org_id", …)} onwards is ONE tenant's work — the handler reads and writes that
     * org's rows, the artifact it registers is that org's document, the audit row it writes is that
     * org's trail — so it is re-pinned from the claimed job's own {@code org_id} before
     * {@code runClaimed}. That pin is what lets every other statement in {@code ExchangeJobStore} stay
     * unqualified, and it is the only form that will still be right when a tenant is promoted to a
     * schema of its own.
     *
     * <p><b>PHASE 3 REPLACED THE PER-HOME SWEEP WITH A LOOKUP</b> (ADR 0010 §2.1). This used to try
     * every home in turn and take the first job it found, which cost one scan per home whether or not
     * any of them had work, and gave whichever home sorted first a permanent claim on the worker.
     * {@code platform.queue_signal} answers "which scope has work" in one indexed read, so a scope with
     * nothing queued is never visited — and because the release stamps a serviced scope with
     * {@code greatest(remaining, now())}, a tenant sitting on a hundred queued exports takes one job and
     * goes behind everyone who has been waiting longer. Jobs are heavyweight and run one at a time per
     * instance, so that ordering is the only fairness this queue has.
     *
     * <p>An empty probe is not a failure: the signal outlived its work (retention purged the last
     * terminal row, a job was cancelled), so it is released — which deletes it when the scope has
     * nothing left — and the next scope is tried, up to {@link #MAX_EMPTY_PROBES}.
     */
    public int drainOnce() {
        return TenantContext.callAsPlatform(this::claimAndRunOne);
    }

    /**
     * Takes scopes from the signal until one yields a job, and runs that job on its own axis.
     *
     * <p>One job per call however many scopes it had to look at: a claim that succeeds returns
     * immediately, so a busy tenant cannot monopolise the loop and a run of empty signals cannot turn
     * one poll into a sweep.
     */
    private int claimAndRunOne() {
        for (int probe = 0; probe < MAX_EMPTY_PROBES; probe++) {
            QueueSignals.Leased leased = signals.claim(ExchangeJobStore.QUEUE, config.staleLock())
                    .orElse(null);
            if (leased == null) {
                // The idle moment: the only one with capacity, and the only one where asking "is
                // 'nothing due' actually true?" costs nobody anything. See reconcileRemoteHomes.
                reconcileRemoteHomes();
                return 0;
            }
            Optional<ExchangeJob> claimed = store.claimOne(config.staleLock(), leased.scope());
            if (claimed.isEmpty()) {
                store.releaseSignal(leased.scope(), leased.lease(), config.staleLock());
                continue;
            }
            // Off the platform axis and onto the job's, for the whole of the run and its bookkeeping. A
            // platform-scoped job (null org_id) stays where it is; anything else is that tenant's work.
            ExchangeJob job = claimed.get();
            try {
                return job.orgId() == null
                        ? runClaimed(job)
                        : TenantContext.callAs(job.orgId(), () -> runClaimed(job));
            } finally {
                // After the run, so the recomputed due_at sees the terminal status — or, for a job
                // released for retry, the locked_at that carries its backoff. On the PLATFORM axis,
                // which is the one this method holds: releaseSignal names its own home.
                store.releaseSignal(leased.scope(), leased.lease(), config.staleLock());
            }
        }
        return 0;
    }

    /**
     * <strong>Finds jobs that exist and are announced by nothing</strong> — ADR 0011 §5's residue sweep,
     * the {@code sweepSearchResidue} shape. Remote homes only, because a co-located tenant's signal
     * still commits with its rows and so cannot be missing; on every deployment with no remote
     * datasource this is an empty loop behind a rate limit.
     *
     * <p>Runs under the platform axis this method already holds ({@code drainOnce} wraps it), pinning
     * each home's own axis only for the query that reads that home's jobs — the two-connection
     * choreography {@code TenantHomeSweep} documents. Homes come from {@link TenantFanOut} so a tenant
     * mid-cutover is not announced into a schema that is being copied.
     */
    private void reconcileRemoteHomes() {
        java.time.Instant now = clock.instant();
        if (now.isBefore(reconcileAfter)) {
            return;
        }
        reconcileAfter = now.plus(RECONCILE_EVERY);
        for (TenantHome home : fanOut.fleet().homes()) {
            if (home.onPrimary()) {
                continue;
            }
            try {
                boolean orphaned = TenantContext.callAs(home.axis(),
                        () -> store.reconcileSignal(home.axis(), config.staleLock()));
                if (orphaned) {
                    log.warn("Exchange jobs for organization {} were queued with no signal to announce"
                            + " them and would never have been claimed; a signal has been raised"
                            + " (ADR 0011 §5 — the cross-database enqueue window)", home.axis());
                }
            } catch (RuntimeException failure) {
                log.error("Exchange signal reconciliation failed for home {} (continuing; the next pass"
                        + " retries)", home.schema(), failure);
            }
        }
    }

    private int runClaimed(ExchangeJob job) {
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
