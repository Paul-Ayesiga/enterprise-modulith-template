package ug.co.smsone.exchange.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.PLATFORM;
import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.metrics.PurgeMetrics;
import ug.co.smsone.shared.retention.RetentionOverrides;
import ug.co.smsone.shared.retention.RetentionPurges;
import ug.co.smsone.shared.retention.RetentionScope;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHomeSweep;

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

    /**
     * How long the whole run may take, against the {@code PT30M} lease — the same five-minute margin
     * {@code SoftDeletePurgeJob} and {@code UsageExportJob} take, and it is here because Phase 5 turned
     * the tenant span into (silos + 1) spans each carrying its own per-override pass.
     */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(25);

    /**
     * The share of {@link #RUN_DEADLINE} the PLATFORM span may spend, so the growing half is not starved
     * by the fixed one. {@code platform.exchange_job} is one schema now and one schema after Phase 7;
     * the tenant span is the only half whose cost scales with the fleet. Same shape and same argument as
     * {@code SoftDeletePurgeJob.PLATFORM_BUDGET}, and equally a floor rather than a measurement — an
     * early-finishing platform span hands its remainder straight to the tenant sweep, because the sweep's
     * deadline is computed from the run's start rather than from where the platform span stopped.
     */
    private static final Duration PLATFORM_BUDGET = RUN_DEADLINE.dividedBy(3);

    /** Which home the tenant span is on and where the rotation resumes — see {@link TenantHomeSweep}. */
    private final TenantHomeSweep homes = new TenantHomeSweep("Exchange-job retention");

    private final ExchangeJobStore store;
    private final ExchangeProperties config;
    private final RetentionOverrides retentionOverrides;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final TenantFanOut fanOut;

    ExchangeRetentionJob(ExchangeJobStore store, ExchangeProperties config,
            RetentionOverrides retentionOverrides, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters, TenantFanOut fanOut) {
        this.store = store;
        this.config = config;
        this.retentionOverrides = retentionOverrides;
        this.clock = clock;
        this.meters = meters;
        this.fanOut = fanOut;
    }

    /**
     * <b>Two pinned spans, because {@code exchange_job} is a SPLIT table and the two homes are two
     * different populations</b> (ADR 0010 §2 row 10). A null {@code org_id} means a platform-scoped
     * handler and lives in {@code platform.exchange_job}; every other job is a tenant's exchange
     * history and lives in that tenant's schema. One pin can only ever reach one of them, because
     * {@link ExchangeJobStore#purgeTerminalBatch} is the axis-routed statement — deliberately, since it
     * is the form that still works when a tenant is promoted.
     *
     * <p>The TENANT span is the one that carries the override machinery, and it has to: per-org
     * retention lives in {@code org_retention_override}, itself tenant-tier, so
     * {@link RetentionPurges}'s very first read failed on the platform axis this job used to take —
     * before a single row was considered. Platform-scoped jobs cannot be overridden at all (there is no
     * org to carry the contract), which is why the platform span is a plain drain at the default cutoff
     * and not a second two-pass purge.
     *
     * <p>Order does not matter here — the two homes share no key and no constraint — so it runs
     * platform first only to match {@code SplitTables.homes()} and the worker's claim order.
     *
     * <p><b>AXIS: PLATFORM and TENANT, two spans, because the table is SPLIT</b> — the paragraphs above
     * are the derivation, and this is the second of the three jobs the {@link JobAxis} two-valued form
     * exists for.
     *
     * <p><b>CURSOR: the delete, in both spans.</b> Every batch removes terminal rows past their cutoff,
     * so a pass stopped by {@link #MAX_BATCHES} — or by a lease that expired under it — leaves the
     * untouched remainder as the next night's head. Nothing is re-examined and nothing is skipped, so
     * there is no position worth keeping in memory. The one place that is subtler than it looks is
     * {@link RetentionPurges}' second pass: it iterates the override map, so an override added while a
     * run is in flight is simply picked up by the next run rather than missed — the map is re-read at
     * the start of every pass.
     *
     * <p><b>LEASE: PT30M against {@link #RUN_DEADLINE}, split by {@link #PLATFORM_BUDGET} and then again
     * per home.</b> The tenant span is now (silos + 1) × (one default drain + that home's own
     * overrides), and the override pass is per-ORG inside each home — which is why the bound is a
     * deadline threaded all the way down to {@code RetentionPurges.Bounds} rather than a batch cap: a
     * cap can only see one pass, and what overruns a lease here is the product of three loops. The
     * per-batch cost against a real {@code exchange_job} has never been measured; the twenty-five
     * minutes rests on 100 batches of 1,000 being comfortable, which is arithmetic, and the deadline is
     * what turns a wrong guess into a WARN and a resumed rotation instead of two replicas purging the
     * same rows.
     */
    @Scheduled(cron = "${app.scheduler.exchange-retention-cron:0 45 4 * * *}")
    @SchedulerLock(name = "exchange-job-retention", lockAtMostFor = "PT30M")
    @JobAxis({PLATFORM, TENANT})
    public void purgeExpiredJobs() {
        Instant now = clock.instant();
        Instant runDeadline = now.plus(RUN_DEADLINE);
        int platformScoped = TenantContext.callAsPlatform(
                () -> drainDefault(now.minus(config.retention()), now.plus(PLATFORM_BUDGET)));
        // One tenant span per HOME. A promoted tenant's exchange history and its retention override are
        // both in its own schema, so the single pooled span this replaced purged neither — and said so
        // nowhere.
        int[] tenantOwned = {0};
        TenantHomeSweep.Swept swept = homes.over(fanOut.fleet().homes(), clock, runDeadline,
                (home, deadline) -> tenantOwned[0] += RetentionPurges.purge(retentionOverrides,
                        RetentionScope.EXCHANGE_JOB, now, config.retention(),
                        new RetentionPurges.Bounds(BATCH_SIZE, MAX_BATCHES, clock, deadline),
                        store::purgeTerminalBatch, store::purgeTerminalBatchForOrg));
        int total = platformScoped + tenantOwned[0];
        // One metric for the run, not one per span and not one per home: "exchange_job" is one table's
        // retention however many homes it has, and splitting the counter would silently change what the
        // dashboards read on the day the first tenant is promoted.
        PurgeMetrics.purged(meters, "exchange-job-retention", "exchange_job", total);
        log.info("Purged {} terminal exchange jobs (default retention {}): {} platform-scoped, {} "
                        + "tenant-owned across {} home(s)",
                total, config.retention(), platformScoped, tenantOwned[0], swept.visited());
        swept.rethrowFirstFailure();
    }

    /**
     * The platform home's drain: every terminal job older than the default cutoff, nothing excluded.
     * Bounded the same way {@link RetentionPurges} bounds its own passes, so one span cannot outrun the
     * ShedLock lease.
     */
    private int drainDefault(Instant cutoff, Instant deadline) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            // Never before the first batch, so the platform half always does some work however tight the
            // budget — the same rule the tenant half follows through RetentionPurges.Bounds.
            if (batch > 0 && !clock.instant().isBefore(deadline)) {
                return total;
            }
            int deleted = store.purgeTerminalBatch(cutoff, List.of(), BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }
}
