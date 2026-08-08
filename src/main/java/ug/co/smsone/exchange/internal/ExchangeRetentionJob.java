package ug.co.smsone.exchange.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.PLATFORM;
import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
     * The tenant axis the org half of this run needs. Names no organization deliberately: an org in no
     * {@code organization} row can only ever resolve to the shared {@code tenant_pool}, so this IS the
     * pooled schema's axis — the same constant and reasoning as {@code MappedSchemaValidator} and
     * {@code WebhookSecretEncryptionMigrator}.
     *
     * <p>PHASE 5 makes the tenant span a LOOP over {@code platform.tenant_placement}: "every org's
     * jobs" is one schema today and one per silo afterwards. The platform span below stays exactly one.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

    private final ExchangeJobStore store;
    private final ExchangeProperties config;
    private final RetentionOverrides retentionOverrides;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    ExchangeRetentionJob(ExchangeJobStore store, ExchangeProperties config,
            RetentionOverrides retentionOverrides, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.store = store;
        this.config = config;
        this.retentionOverrides = retentionOverrides;
        this.clock = clock;
        this.meters = meters;
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
     * <p><b>LEASE: PT30M, sized for TWO drains of {@link #MAX_BATCHES} × {@link #BATCH_SIZE} rows each
     * plus one drain per per-org override, all against ONE tenant schema.</b> That last clause is the
     * part Phase 5 breaks and it breaks worse here than in most of these jobs, because the override
     * pass is per-ORG inside each home: the tenant span becomes (silos + 1) × (1 default drain + its
     * own overrides). PT30M is not sized for that and must be re-derived — with a resumable per-home
     * cursor at the same time, since a fan-out cut at home 40 of 200 that restarts at home 1 the next
     * night never reaches the tail. The measured input that derivation will need is the per-batch cost
     * against a real {@code exchange_job}, which nobody has taken; today's PT30M rests on 100 batches of
     * 1,000 being comfortable in half an hour, which is true by a wide margin and is not a measurement
     * either.
     */
    @Scheduled(cron = "${app.scheduler.exchange-retention-cron:0 45 4 * * *}")
    @SchedulerLock(name = "exchange-job-retention", lockAtMostFor = "PT30M")
    @JobAxis({PLATFORM, TENANT})
    public void purgeExpiredJobs() {
        Instant now = clock.instant();
        int platformScoped = TenantContext.callAsPlatform(
                () -> drainDefault(now.minus(config.retention())));
        // PHASE 5: one span per home rather than one for the pool — see POOLED_TENANT.
        int tenantOwned = TenantContext.callAs(POOLED_TENANT, () -> RetentionPurges.purge(retentionOverrides,
                RetentionScope.EXCHANGE_JOB, now, config.retention(), BATCH_SIZE, MAX_BATCHES,
                store::purgeTerminalBatch, store::purgeTerminalBatchForOrg));
        int total = platformScoped + tenantOwned;
        // One metric for the run, not one per span: "exchange_job" is one table's retention however
        // many homes it has, and splitting the counter would silently change what the dashboards read.
        PurgeMetrics.purged(meters, "exchange-job-retention", "exchange_job", total);
        log.info("Purged {} terminal exchange jobs (default retention {}): {} platform-scoped, {} tenant-owned",
                total, config.retention(), platformScoped, tenantOwned);
    }

    /**
     * The platform home's drain: every terminal job older than the default cutoff, nothing excluded.
     * Bounded the same way {@link RetentionPurges} bounds its own passes, so one span cannot outrun the
     * ShedLock lease.
     */
    private int drainDefault(Instant cutoff) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = store.purgeTerminalBatch(cutoff, List.of(), BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }
}
