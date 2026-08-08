package ug.co.smsone.billing.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.PLATFORM;
import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHome;

/**
 * The metered-billing bridge: closed days from the api_usage_daily ledger become Kill Bill usage
 * records on the org's ACTIVE subscription. Opt-in (BILLING_USAGE_EXPORT) because it only prices
 * when the KB catalog defines the unit — a simple-plan catalog silently records nothing billable.
 * Double-billing is guarded twice over: the {@code exported} flag here, KB's trackingId dedup there.
 * Per-org failures log and continue; the days wait for the next night.
 *
 * <p><b>One request per ORG, not one per day.</b> {@code /1.0/kb/usages} accepts a list of usage
 * records under one unitUsageRecord, so an org's whole unexported backlog is a single POST and a
 * single UPDATE. That is the difference between a run that is O(rows) in remote round trips — 5000
 * orgs x a 30-day backlog is 150,000 synchronous POSTs, which does not fit in the PT30M lease at any
 * plausible latency — and one that is O(orgs).
 *
 * <p><b>Why the backlog is bounded by a day window and not by a LIMIT.</b> The obvious fix for an
 * unbounded scan is {@code limit n}, and here it is a trap that turns a slow job into a silent total
 * outage. Rows for an org with no Kill Bill subscription (FREE is deliberately unmapped) are skipped
 * and never marked exported, nothing purges the ledger, and the query orders by day ASCENDING — so
 * those permanently-stuck rows are by construction the oldest, they accumulate forever, and the first
 * night they exceed {@code n} a bare LIMIT returns nothing but them. Every billable day starves,
 * quietly, with the job still logging success. The window is the honest bound instead: it caps the
 * scan at (orgs x window) rows, it never grows, and what it excludes is counted and logged rather
 * than skipped in silence. A day still unexported after the window has failed every night since it
 * closed; it is not going to succeed on the next one, and Kill Bill will not price it into an invoice
 * that closed weeks ago either.
 */
@Component
@ConditionalOnProperty(name = "app.billing.usage-export.enabled", havingValue = "true")
class UsageExportJob {

    private static final Logger log = LoggerFactory.getLogger(UsageExportJob.class);

    /**
     * Five minutes under the {@code PT30M} lease, so a Kill Bill that answers slowly rather than failing
     * cannot run the pass past the lock and into a second instance's concurrent run.
     *
     * <p><b>Re-derived rather than inherited, and the derivation is a division.</b> The pass is one
     * synchronous POST per org with a backlog, so the deadline buys {@code 25 min / (KB latency per
     * org)} orgs a night: at 250 ms that is ~6,000, at one second ~1,500, at three seconds ~500. Those
     * are arithmetic, not measurements — no Kill Bill latency has been measured against this
     * installation — so the honest statement is that the number of orgs one night covers is unknown
     * within an order of magnitude, and {@link Export#cutShort()} plus the warning it logs is what makes
     * the real answer observable instead of inferred.
     *
     * <p>What it is NOT sized by is schema count, and that is worth saying because most of this
     * subsystem's Phase 5 notes are about fan-out. The dominant cost here is remote and per-ORG; the
     * per-home {@code billing_account} read is one indexed row. Promoting a hundred tenants to silos
     * therefore changes the connection pattern and not the deadline.
     */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(25);


    private final JdbcTemplate jdbc;
    private final BillingAccountRepository accounts;
    private final KillBillGateway killBill;
    private final Clock clock;
    private final TenantFanOut fanOut;
    private final String unitType;
    private final int maxBacklogDays;

    UsageExportJob(JdbcTemplate jdbc, BillingAccountRepository accounts, KillBillGateway killBill, Clock clock,
            TenantFanOut fanOut,
            @Value("${app.billing.usage-export.unit:api-requests}") String unitType,
            @Value("${app.billing.usage-export.max-backlog-days:45}") int maxBacklogDays) {
        if (maxBacklogDays < 1) {
            // Fails at startup rather than at 04:20: a window below one day can never contain a closed
            // day (`day < current_date`), so the job would run nightly and export nothing at all.
            throw new IllegalStateException(
                    "app.billing.usage-export.max-backlog-days must be at least 1, was " + maxBacklogDays);
        }
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.killBill = killBill;
        this.clock = clock;
        this.fanOut = fanOut;
        this.unitType = unitType;
        this.maxBacklogDays = maxBacklogDays;
    }

    record UsageRow(UUID orgId, LocalDate day, long requests) {
    }

    /** What one pass did, returned so a test can assert on it rather than parse log lines. */
    record Export(int batches, int days, int unbillableOrgs, boolean cutShort) {
    }

    /**
     * <b>AXIS: TENANT, one span, argued in {@link #export()}</b> — the ledger is platform-tier and named,
     * so it resolves from any axis, while {@code billing_account} is the tenant's and is reached bare;
     * the asymmetry is what picks the axis.
     *
     * <p><b>CURSOR: persisted in the data, in {@code platform.api_usage_daily.exported}, and it is the
     * shape ADR 0010 §3.4 tells the other fan-out jobs to generalise.</b> Nothing is held in memory:
     * the scan selects {@code exported = false} ordered by {@code day} ascending, so the orgs a
     * deadline-cut run never reached still hold the OLDEST unexported days and sort to the front of
     * tomorrow's scan by construction. The position is shared between replicas (it is a committed
     * column) and survives restarts, which is strictly better than the in-memory cursors
     * {@code SoftDeletePurgeJob} and {@code IdentityReconciliationJob} have to settle for — those sweep
     * tables with no per-row "done" flag to write.
     *
     * <p>The {@code max-backlog-days} window is the second half of the same idea and is documented on
     * the class: it is what stops permanently-unexportable days (an org with no Kill Bill subscription)
     * accumulating at the head of that ordering and starving everything behind them. A cursor with no
     * such window is a cursor that eventually parks on a poisoned prefix.
     *
     * <p><b>LEASE: PT30M against {@link #RUN_DEADLINE}</b>, both re-derived there, and the org count one
     * night covers is honestly a division rather than a measurement.
     */
    @Scheduled(cron = "${app.scheduler.usage-export-cron:0 20 4 * * *}")
    @SchedulerLock(name = "billing-usage-export", lockAtMostFor = "PT30M")
    @JobAxis({PLATFORM, TENANT})
    public void run() {
        export();
    }

    /**
     * Declares the axis for the pass (ADR 0010 §3.4). Pinned here rather than in {@link #run()}
     * because a test calls this method directly, and the axis has to be a property of the work, not of
     * how it was triggered.
     *
     * <p><b>A PLATFORM span with a nested per-org one since Phase 5, and the asymmetry that decides it
     * has inverted.</b> {@code api_usage_daily} is platform-tier and every statement below names it
     * {@code platform.api_usage_daily}, so the scan, {@link #markExported} and {@link #agedOutDays()}
     * would resolve from any axis. {@code billing_account} is tenant-tier and reached BARE — and it is
     * read PER ORG, so with silos in the fleet there is no single tenant axis that reaches all of them.
     * One pooled pin used to cover every org because every org was pooled; that same pin now reaches
     * every org except the promoted ones, and for those it returns no row, which this pass reads as "no
     * billable subscription". The platform pin is the honest outer span, and the org's own home is
     * pinned around the one read that needs it (see {@link #activeSubscriptionOf}).
     *
     * <p><b>This is deliberately NOT a {@code TenantHomeSweep}, and the reason is the ledger.</b> The
     * other seven fanned-out jobs iterate homes because their input lives in the homes. This one's input
     * is one cross-tenant scan of {@code platform.api_usage_daily} ordered by day, and that ordering IS
     * the fairness property the job is built on — regrouping the backlog by home would replace "the
     * oldest unexported days go first" with "the pool's days go first", which is a different and worse
     * job. So the scan and the grouping stay exactly as they were and the home is resolved per org.
     *
     * <p>What did NOT move is the ledger. {@code api_usage_daily} stayed platform-tier through the
     * Phase 2 split deliberately, because the cross-tenant {@code order by day} below is the fairness
     * property this job is built on and a per-tenant ledger cannot express it (ADR 0010 §2).
     */
    Export export() {
        return TenantContext.callAsPlatform(this::exportBacklog);
    }

    private Export exportBacklog() {
        // One snapshot for the run, so the loop cannot disagree with itself about where an org lives
        // half way through — and so a tenant that begins a promotion mid-run is skipped consistently
        // rather than exported from one schema and marked exported in another.
        TenantFanOut.Fleet fleet = fanOut.fleet();
        // ORDER BY day ascending, and the grouping below preserves it, which buys two things. Within a
        // batch the records reach Kill Bill oldest-first. Across nights it is what makes a run cut
        // short by the deadline FAIR: the orgs it never reached still hold the oldest unexported days,
        // so tomorrow they sort to the front. A fixed head can never be re-examined forever while a
        // tail starves — the failure mode `IdentityReconciliationJob` documents for its own scan.
        //
        // Both bounds are computed by the database (`current_date`), not by the JVM: mixing the two
        // clocks would move the window's edges independently around midnight.
        List<UsageRow> rows = jdbc.query("""
                select org_id, day, requests from platform.api_usage_daily
                where exported = false
                  and day < current_date
                  and day >= current_date - cast(? as integer)
                order by day
                """, (rs, i) -> new UsageRow(rs.getObject("org_id", UUID.class),
                rs.getDate("day").toLocalDate(), rs.getLong("requests")), maxBacklogDays);

        Map<UUID, List<KillBillGateway.UsageDay>> backlog = new LinkedHashMap<>();
        for (UsageRow row : rows) {
            backlog.computeIfAbsent(row.orgId(), org -> new ArrayList<>())
                    .add(new KillBillGateway.UsageDay(row.day(), row.requests()));
        }

        // Resolved once per ORG, not once per (org, day). Grouping already visits each org exactly
        // once, so the map is no longer what stops a thirty-day backlog making thirty identical
        // billing_account reads and thirty identical Kill Bill round trips — but it is still the one
        // place that caches the NEGATIVE answer, and it keeps that guarantee independent of how the
        // loop below is later shaped. computeIfAbsent stores only successful resolutions: a remote
        // failure propagates to the catch below and the org is retried on the next run.
        Map<UUID, Optional<UUID>> billableSubscriptions = new HashMap<>();
        Instant deadline = clock.instant().plus(RUN_DEADLINE);
        int batches = 0;
        int days = 0;
        int unbillableOrgs = 0;
        int frozenOrgs = 0;
        boolean cutShort = false;
        for (Map.Entry<UUID, List<KillBillGateway.UsageDay>> entry : backlog.entrySet()) {
            if (clock.instant().isAfter(deadline)) {
                cutShort = true;
                break;
            }
            UUID orgId = entry.getKey();
            List<KillBillGateway.UsageDay> batch = entry.getValue();
            TenantHome home = fleet.homeOf(orgId).orElse(null);
            if (home == null) {
                // Mid-provision: its rows are being copied and this run must not read a billing account
                // that is about to move, nor mark days exported in a schema the tenant is leaving. The
                // days stay unexported, which puts them at the head of tomorrow's `order by day` — the
                // same fairness the deadline-cut case relies on, for free.
                frozenOrgs++;
                continue;
            }
            UUID subscriptionId;
            try {
                subscriptionId = billableSubscriptions
                        .computeIfAbsent(orgId, org -> activeSubscriptionOf(home, org))
                        .orElse(null);
            } catch (RuntimeException e) {
                log.warn("Resolving the Kill Bill subscription for org {} failed — its {} unexported "
                        + "day(s) wait for the next run: {}", orgId, batch.size(), e.toString());
                continue;
            }
            if (subscriptionId == null) {
                unbillableOrgs++;
                continue; // no billable subscription — leave the days for when there is one
            }
            String trackingId = trackingId(orgId, batch);
            try {
                killBill.recordUsage(subscriptionId, unitType, batch, trackingId);
            } catch (RuntimeException e) {
                log.warn("Usage export for org {} ({} day(s) {}..{}) failed before Kill Bill accepted it "
                                + "— will retry next run: {}",
                        orgId, batch.size(), batch.getFirst().date(), batch.getLast().date(), e.toString());
                continue;
            }
            // Deliberately its own try, and its own log level. Everything above this line is safe to
            // repeat; this is the one step whose failure Kill Bill's dedup cannot cover, because the
            // next run's batch will be a SUPERSET of this one and therefore carries a different
            // trackingId. See trackingId(..) — the alternative to that window is a request per day.
            try {
                markExported(orgId, batch);
            } catch (RuntimeException e) {
                log.error("Kill Bill ACCEPTED usage batch {} for org {} ({} day(s) {}..{}) but the ledger "
                                + "was not marked exported: {}. The next run posts these days again under a "
                                + "different trackingId, so they can be recorded twice — reconcile against "
                                + "Kill Bill before the period closes.",
                        trackingId, orgId, batch.size(), batch.getFirst().date(), batch.getLast().date(),
                        e.toString());
                continue;
            }
            batches++;
            days += batch.size();
        }

        long agedOut = agedOutDays();
        if (agedOut > 0) {
            log.warn("{} unexported usage day(s) are older than the {}-day export window and were not "
                            + "considered. Days an org can NEVER export (no Kill Bill subscription — FREE is "
                            + "deliberately unmapped) settle here and accumulate; a count that climbs steadily "
                            + "is that, a count that jumps is a billable org that has been failing all month.",
                    agedOut, maxBacklogDays);
        }
        if (cutShort) {
            log.warn("Usage export stopped at its {} deadline after {} org batch(es) — the rest wait for "
                    + "the next run, where their days are the oldest and sort first. Is Kill Bill slow?",
                    RUN_DEADLINE, batches);
        }
        if (frozenOrgs > 0) {
            log.info("Usage export skipped {} org(s) that are mid-provision — their days stay unexported "
                    + "and sort first on the next run", frozenOrgs);
        }
        if (batches > 0) {
            log.info("Exported {} usage day(s) for {} org(s) to Kill Bill as unit '{}'; {} org(s) skipped "
                    + "for want of a billable subscription", days, batches, unitType, unbillableOrgs);
        }
        return new Export(batches, days, unbillableOrgs, cutShort);
    }

    /**
     * One statement for the whole batch instead of one auto-committed UPDATE per day. The days are
     * spelled out rather than expressed as a BETWEEN range: a range would also flip days this run
     * never sent — a gap in the ledger that a later backfill fills in — and marking a day exported
     * that Kill Bill never saw is the one mistake this job must not make.
     */
    private void markExported(UUID orgId, List<KillBillGateway.UsageDay> batch) {
        Object[] args = new Object[batch.size() + 1];
        args[0] = orgId;
        for (int i = 0; i < batch.size(); i++) {
            args[i + 1] = Date.valueOf(batch.get(i).date());
        }
        jdbc.update("update platform.api_usage_daily set exported = true where org_id = ? and day in ("
                + String.join(", ", Collections.nCopies(batch.size(), "?")) + ")", args);
    }

    /**
     * How many unexported days fell outside the window — the rows this run abandoned. A COUNT rather
     * than a fetch precisely because nothing is going to be done with them here; it is an index-only
     * scan of {@code idx_api_usage_unexported} and the number is the only signal that separates the
     * expected sediment (orgs that can never export) from a billable org stuck for longer than the
     * window.
     */
    private long agedOutDays() {
        Long agedOut = jdbc.queryForObject("""
                select count(*) from platform.api_usage_daily
                where exported = false and day < current_date - cast(? as integer)
                """, Long.class, maxBacklogDays);
        return agedOut == null ? 0 : agedOut;
    }

    /**
     * The Kill Bill idempotency key for ONE batch, and the subtlest thing in this class.
     *
     * <p>Kill Bill dedups a trackingId WHOLESALE — a request carrying an id it already holds for the
     * subscription is dropped in full, not merged — so the key must be a function of the exact day set
     * being sent. A stable key over a coarser partition (org + month, say) reads as the safer choice
     * and is the opposite: the second post of a grown backlog would be discarded and the days added
     * since the first post would never be billed at all.
     *
     * <p>A single-day batch keeps the pre-batching key verbatim, {@code usage-<org>-<day>}. That is not
     * nostalgia — one closed day per org per night IS the steady state, so the steady-state key is
     * unchanged by this rewrite and a day already posted by the previous version can never be posted a
     * second time under a new name.
     *
     * <p>Amounts are deliberately outside the key. A ledger row whose count is corrected after export
     * re-posts under the same id and Kill Bill ignores it, which is the same answer the per-day key
     * gave; the day set is what the batch IS.
     *
     * <p>What no key can cover: if Kill Bill accepts a batch and the ledger update behind it fails,
     * tomorrow's batch is a superset, carries a different key, and Kill Bill records the overlap
     * again. That window is one statement wide and is logged at ERROR naming this id — the only way to
     * close it entirely is a request per day, which is the run this replaced.
     */
    static String trackingId(UUID orgId, List<KillBillGateway.UsageDay> batch) {
        if (batch.size() == 1) {
            return "usage-" + orgId + "-" + batch.getFirst().date();
        }
        StringJoiner days = new StringJoiner(",");
        batch.forEach(day -> days.add(day.date().toString()));
        // The range alone would not do: {1st, 2nd, 5th} and {1st, 3rd, 5th} share first, last AND
        // count, and are different sets. The range is there to make the id readable in a KB support
        // query; the digest is what makes it correct.
        return "usage-" + orgId + "-" + batch.getFirst().date() + "-" + batch.getLast().date()
                + "-" + digest(days.toString());
    }

    /** 64 bits of SHA-256 over the day list — ample to separate two day sets of one org. */
    private static String digest(String days) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(days.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    /**
     * The org's one billable subscription, or empty when it has no billing account or none ACTIVE.
     *
     * <p>{@code billing_account} is tenant-tier (ADR 0010 §2) and is read BARE, so it needs the ORG'S
     * OWN axis — which is why the home is a parameter. Before Phase 5 the pass pinned the pool once and
     * that answered for everyone; after the first promotion the same pin answered {@code empty} for the
     * promoted tenant, which this job reads as "no billable subscription" and counts as
     * {@code unbillableOrgs}. Its usage would have sat unexported until it aged past
     * {@code max-backlog-days} and then been dropped from the window entirely — revenue lost to a log
     * line that says a healthy-looking number of orgs were skipped.
     */
    private Optional<UUID> activeSubscriptionOf(TenantHome home, UUID orgId) {
        // The account read is pinned to the ORG'S OWN home and the Kill Bill call is deliberately
        // outside that pin. Two reasons, and both are house rules: a remote round trip inside a pinned
        // span is a remote round trip inside whatever the span opens (AGENTS §4.3), and pinning is a
        // thread-local, so leaving it in place across a multi-second HTTP call would make the next
        // borrow on this thread — the markExported below — land on the tenant's schema rather than on
        // the platform axis this pass runs on.
        UUID kbAccountId = TenantContext.callAs(home.axis(),
                () -> accounts.findByOrgId(orgId).map(BillingAccount::getKbAccountId).orElse(null));
        if (kbAccountId == null) {
            return Optional.empty();
        }
        return killBill.subscriptions(kbAccountId).stream()
                .filter(sub -> "ACTIVE".equalsIgnoreCase(sub.state()))
                .findFirst()
                .map(sub -> UUID.fromString(sub.subscriptionId()));
    }
}
