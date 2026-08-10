package ug.co.smsone.support.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.PLATFORM;
import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHome;
import ug.co.smsone.shared.tenancy.TenantHomeSweep;

/**
 * The reconciler {@link TicketIndex} owes the codebase. ADR 0010 §8 Q2 is explicit — <b>no projection
 * ships without its reconciler</b> — and this repository has already proved the rule twice:
 * {@code SoftDeletePurgeJob.sweepSearchResidue} exists because {@code search_document} had no delete
 * event, and {@code OrgMembershipIndexReconciler} exists because a cross-tier projection cannot have the
 * foreign key that would have kept it honest. This is the third, shipped in the same change-set as the
 * projection rather than in the one after it.
 *
 * <p><b>Here the reconciler is not a backstop for the delete path — it IS the delete path.</b> Nothing
 * in the application soft-deletes a ticket, so every disappearance is raw SQL that cannot know this
 * projection exists: {@code SoftDeletePurgeJob} hard-deleting an aged-out row (it must use raw SQL,
 * because {@code @SQLRestriction} hides exactly the rows it exists to remove), {@code
 * SoftDeleteRecovery.restore} bringing one back, a tenant schema dropped whole. Add the two paths that
 * produce the other direction — every ticket that existed before V61, which no migration could have
 * backfilled because the platform sequence runs before any tenant schema is reachable and after Phase 5
 * a backfill would have to enter every silo; and a cross-database write whose tenant half then rolled
 * back (ADR 0011 §5.1) — and the disagreement is guaranteed, not hypothetical.
 *
 * <h2>Two arms, deliberately independent</h2>
 *
 * <p>{@link #refresh} walks the home's live tickets and upserts them; {@link #sweep} walks the index's
 * rows and deletes the ones the home no longer has. Each has its own resumable cursor and neither
 * depends on the other having finished.
 *
 * <p><b>That independence is a correctness property, not a tidiness one.</b> The obvious implementation
 * — collect every live ticket id, then delete every index row not in that set — is one deadline away
 * from catastrophe: a run cut half way through the collection would delete the whole tail of a tenant's
 * queue rows, on the night nobody connected the two facts. So the sweep never reasons from absence. It
 * takes a page of index rows and asks the tenant, in one statement, which of <em>those</em> ids are
 * live; anything the tenant does not name is residue for that page and for no other. A cut sweep is
 * then simply a shorter sweep.
 *
 * <h2>Fanned out over HOMES, and both arms cross a database boundary</h2>
 *
 * <p>{@link TenantHomeSweep} is the idiom (ADR 0010 §3.4): each home visited on its own axis, inside a
 * per-home budget, resuming next run where this one was cut, per-home failure isolation, and the run
 * still fails. What this job adds is that <b>neither arm can be one SQL statement</b>. Since ADR 0011 a
 * home may live on another database, so {@code insert into platform.ticket_index select … from ticket}
 * is not a statement that can run at all — the two relations are on two servers. Every page therefore
 * moves through the JVM: read on the tenant's axis, write on the platform's, one shape for both
 * topologies. {@link TicketIndex} takes the platform hop through {@code CrossDatabaseWrites}, which is
 * the same connection and the same transaction whenever the two are co-located, so a deployment with no
 * remote datasource pays a {@code ConcurrentHashMap} lookup and nothing else.
 *
 * <h2>The pool is the one home whose organizations are not enumerable</h2>
 *
 * <p>A silo is one organization ({@code silo-per-org}, commit 0822943), so both arms scope to
 * {@code org_id = ?} and use {@code idx_ticket_index_org} / {@code idx_ticket_org} exactly. The pool
 * cannot: {@code TenantRoutes.readHome} answers {@code tenant_pool} for a tenant the registry has never
 * heard of, so "the pooled organizations" is not a set any table holds. Its arms therefore walk without
 * an org predicate and ask the ROUTER, per row, whether that organization really belongs to this home —
 * {@link TenantFanOut.Fleet#homeOf}, the same answer the request path routes by.
 *
 * <p><b>That filter is what stops this job from repeating the failure {@code OrgMembershipIndexReconciler}
 * documents.</b> A fleet-wide pass against the pool would find no tickets for a promoted tenant and
 * delete every one of its queue rows — silently, on the night after a promotion nobody thought to
 * connect it to. {@code homeOf} refuses a tenant that is frozen, mid-promotion, half-provisioned or on a
 * datasource this deployment cannot reach, so those tenants are skipped by BOTH arms rather than
 * reconciled against a schema their rows are not in.
 */
@Component
class TicketIndexReconciler {

    private static final Logger log = LoggerFactory.getLogger(TicketIndexReconciler.class);

    /**
     * Rows per round trip. Large enough that a silo of a few thousand tickets finishes in a handful of
     * batches, small enough that one page is bounded heap on a scheduler thread whatever a tenant's
     * ticket count is — which matters here more than in the sibling reconcilers, because a page of this
     * projection is twelve columns rather than three.
     */
    private static final int PAGE = 500;

    /** The nil UUID: lower than every real one, so a fresh cursor starts at the beginning. */
    private static final UUID SCAN_START = new UUID(0L, 0L);

    /**
     * How long a whole run may take before it stops itself, against the {@code PT30M} lease below.
     *
     * <p><b>Re-derived for this job's fan-out rather than copied</b> (ADR 0010 §3.4). The five-minute
     * margin is the same one {@code SoftDeletePurgeJob}, {@code UsageExportJob},
     * {@code IdentityReconciliationJob} and {@code OrgMembershipIndexReconciler} take, and it is here for
     * the reason Phase 5 wrote down: a lease that expires under a running pass does not produce a slow
     * job, it lets a second replica acquire the lock and reconcile the same rows concurrently — and two
     * reconcilers racing is how a correct upsert loses to a stale delete.
     *
     * <p><b>The worst case this is sized against, stated so the next person can argue with it.</b>
     * {@link TenantHomeSweep} gives {@code tenant_pool} half the remaining budget and splits the rest
     * across the silos, so at ADR 0010 §8 Q1's ceiling of 200 silos each silo gets roughly
     * {@code 12.5 min / 200 ≈ 3.7 s} and the pool gets 12.5 minutes. One silo is one organization, and
     * its visit is {@code ceil(tickets / 500)} round trips per arm — so 3.7 s covers a silo with
     * thousands of tickets and is nowhere near covering one with a million. That is not a hole, because
     * both cursors resume: a home too large for its slice drains over consecutive nights instead of
     * redoing its head forever. What would be a hole is a run that outlived the lease, and this is the
     * bound that stops it.
     *
     * <p>If a production run logs {@link TenantHomeSweep}'s deadline warning regularly, the answer is to
     * measure a page and re-derive this and the lease TOGETHER — raising the lease alone moves the
     * overrun rather than removing it.
     */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(25);

    /**
     * Which home this job is on and where the rotation resumes — one instance, held as a field because
     * the position is this job's and has to outlive a run (ADR 0010 §3.4).
     */
    private final TenantHomeSweep homes = new TenantHomeSweep("Ticket-index reconciliation");

    /**
     * Where each home's two arms resume, keyed by SCHEMA NAME.
     *
     * <p>Keyed by schema rather than by organization for the reason {@code SoftDeletePurgeJob.resumeAt}
     * gives: the unit a cursor is meaningful in is the unit the sweep visits, and a map keyed by tenant
     * would grow with the tenant base and never shrink. Concurrent rather than a plain map, and not
     * because two runs overlap — ShedLock stops that — but for VISIBILITY: consecutive nightly runs land
     * on whichever scheduler thread is free, and a plain field written by one and read by another a night
     * later has no happens-before edge between them. The failure would be silent and would look exactly
     * like the bug the cursor removes.
     *
     * <p>In memory, with the trade this codebase has now stated four times: it survives across nights
     * inside one process (a pod lives for days, which is the case that matters), resets to the head on
     * restart — safe, because every statement here is idempotent, so redoing a prefix costs time and
     * nothing else — and is not shared between replicas, so an alternating ShedLock winner produces
     * overlap rather than starvation. Overlap is waste; starvation is a support desk that cannot see a
     * customer's ticket. The durable version belongs in a {@code platform} table beside
     * {@code tenant_placement} and needs a migration this change-set does not own.
     */
    private final Map<String, HomeCursor> resumeAt = new ConcurrentHashMap<>();

    /** Where each arm of one home's reconciliation resumes. {@link #SCAN_START} means "at the head". */
    private record HomeCursor(UUID refreshedTo, UUID sweptTo) {

        private static final HomeCursor HEAD = new HomeCursor(SCAN_START, SCAN_START);
    }

    /**
     * The tenant side of the refresh arm. Unqualified, so the connection's {@code search_path} decides
     * whose tickets these are — the only form that survives promotion (ADR 0010 §2).
     *
     * <p>{@code deleted_at is null} is written out because this is native SQL and
     * {@code @SQLRestriction} does not apply to it (AGENTS §4.2) — miss it and the refresh arm would
     * re-insert queue rows for exactly the tickets the sweep arm is deleting, and the two would fight
     * forever with the log claiming both were working.
     */
    private static final String LIVE_TICKETS_IN_HOME = """
            select id as ticket_id, org_id, opener_person_id, subject, category, priority, status,
                   assignee_person_id, escalated, first_response_at, resolution_due_at, created_at
              from ticket
             where deleted_at is null and id > ?
             order by id
             limit ?
            """;

    /**
     * The same page for a silo, with the organization named.
     *
     * <p>The predicate is redundant in a silo — nobody else's rows are in that schema — and it is kept
     * for ADR 0010 §1's reason verbatim: {@code org_id} is the detector that catches a {@code search_path}
     * mistake, and it is free. A refresh arm that read the wrong schema without it would happily copy
     * another tenant's tickets into this organization's queue rows.
     */
    private static final String LIVE_TICKETS_IN_SILO = """
            select id as ticket_id, org_id, opener_person_id, subject, category, priority, status,
                   assignee_person_id, escalated, first_response_at, resolution_due_at, created_at
              from ticket
             where org_id = ? and deleted_at is null and id > ?
             order by id
             limit ?
            """;

    /**
     * {@link TicketIndex#ROW}, not a copy. The refresh arm reads the projection's twelve columns off the
     * tenant's own table and the sweep arm reads them back off the projection, so both sides map into one
     * record through one mapper — a second mapper here is where a column added to V61 would be picked up
     * by the write path and silently dropped by the repair.
     */
    private static final RowMapper<TicketIndex.Row> ROW = TicketIndex.ROW;

    private final TicketIndex index;
    private final JdbcTemplate jdbc;
    private final TenantFanOut fanOut;
    private final Clock clock;

    TicketIndexReconciler(TicketIndex index, JdbcTemplate jdbc, TenantFanOut fanOut, Clock clock) {
        this.index = index;
        this.jdbc = jdbc;
        this.fanOut = fanOut;
        this.clock = clock;
    }

    /**
     * 04:30 is chosen, not free: {@code SoftDeletePurgeJob} runs at 04:00 and is the main producer of the
     * residue this job removes, so running before it would leave every hard-deleted ticket's queue row
     * standing for a further twenty-four hours. It sits before {@code OrgMembershipIndexReconciler}'s
     * 04:50 only so the two nightly per-tenant fan-outs do not contend for connections while the purge
     * is still finishing.
     *
     * <p><b>AXIS: PLATFORM and TENANT.</b> The fleet read and both halves of every platform statement are
     * platform-tier and run under the pin declared here — a scheduler thread has no axis of its own, so
     * without it the first borrow lands in {@code no_tenant} (ADR 0010 §3.4). The tenant pin is
     * {@link TenantHomeSweep#over}'s, taken per home.
     *
     * <p><b>CURSOR: {@link #resumeAt}, two per home, plus {@link TenantHomeSweep}'s own rotation over the
     * homes themselves.</b> <b>LEASE: {@code PT30M} against {@link #RUN_DEADLINE}</b> — derived on that
     * constant against this fan-out's worst case, which is the thing Phase 5 documented and this job is
     * the fourth to have to do.
     */
    @Scheduled(cron = "${app.scheduler.ticket-index-cron:0 30 4 * * *}")
    @SchedulerLock(name = "ticket-index-reconcile", lockAtMostFor = "PT30M")
    @JobAxis({PLATFORM, TENANT})
    public void reconcile() {
        TenantContext.runAsPlatform(this::reconcileEveryHome);
    }

    /** Package-private so a test can drive one pass without ShedLock's silent same-name relock skip. */
    void reconcileEveryHome() {
        Instant deadline = clock.instant().plus(RUN_DEADLINE);
        TenantFanOut.Fleet fleet = fanOut.fleet();
        Tally tally = new Tally();
        TenantHomeSweep.Swept swept = homes.over(fleet.homes(), clock, deadline,
                (home, homeDeadline) -> reconcileHome(home, homeDeadline, fleet, tally));
        if (tally.refreshed > 0 || tally.removed > 0) {
            // Loud when it repairs anything. Every row this job writes is a row the write path should
            // have written and did not, and every row it deletes is one nothing was ever going to
            // delete — so a quiet count here would hide both behind a job that keeps cleaning up.
            log.warn("Ticket index reconciled across {} tenant homes: {} queue rows refreshed, {} removed"
                            + " for tickets that are gone", swept.visited(), tally.refreshed, tally.removed);
        } else {
            log.debug("Ticket index agreed with every home's tickets across {} homes", swept.visited());
        }
        // Loud AND complete: one unreachable home must not cost every other home its reconciliation, and
        // it must still fail the run. SoftDeletePurgeJob's doctrine, applied to a fan-out.
        swept.rethrowFirstFailure();
    }

    /**
     * One home's two arms, on the axis {@link TenantHomeSweep} has already pinned.
     *
     * <p>Both arms share the home's budget and each checks it, so a home whose refresh runs long does
     * not silently cost the sweep its whole pass — it simply gets less of it, and both cursors remember
     * where they were.
     */
    private void reconcileHome(TenantHome home, Instant deadline, TenantFanOut.Fleet fleet, Tally tally) {
        HomeCursor cursor = resumeAt.getOrDefault(home.schema(), HomeCursor.HEAD);
        UUID refreshedTo = refresh(home, cursor.refreshedTo(), deadline, fleet, tally);
        UUID sweptTo = sweep(home, cursor.sweptTo(), deadline, fleet, tally);
        resumeAt.put(home.schema(), new HomeCursor(refreshedTo, sweptTo));
    }

    /**
     * <b>Arm one — every live ticket in this home has a correct queue row.</b> This is the direction that
     * matters to a customer: a ticket the index does not know about is a problem the support desk never
     * sees, and there is no other route to it.
     *
     * @return where the next run resumes: the last ticket id this pass covered, or {@link #SCAN_START}
     *     when it reached the end of the home
     */
    private UUID refresh(TenantHome home, UUID from, Instant deadline, TenantFanOut.Fleet fleet,
            Tally tally) {
        UUID cursor = from;
        while (clock.instant().isBefore(deadline)) {
            List<TicketIndex.Row> page = home.pooled()
                    ? jdbc.query(LIVE_TICKETS_IN_HOME, ROW, cursor, PAGE)
                    : jdbc.query(LIVE_TICKETS_IN_SILO, ROW, home.axis(), cursor, PAGE);
            if (page.isEmpty()) {
                return SCAN_START;
            }
            cursor = page.getLast().ticketId();
            // The pool holds many tenants, and some of them are not this run's business — mid-promotion,
            // half-provisioned, or routed to a database this deployment cannot reach. `homeOf` is the
            // router's own answer, so filtering by it means the refresh can never copy a tenant's rows
            // out of a schema the tenant is being moved off. A silo is one organization and the fleet
            // already made that decision, so nothing is filtered there.
            List<TicketIndex.Row> mine = home.pooled()
                    ? page.stream().filter(row -> servedByThisHome(fleet, home, row.orgId())).toList()
                    : page;
            tally.refreshed += index.recordAll(mine);
            if (page.size() < PAGE) {
                return SCAN_START;
            }
        }
        return cursor;
    }

    /**
     * <b>Arm two — the index holds nothing this home no longer has.</b> Residue is invisible to everyone
     * except the operator, who sees a ticket in the queue that 404s when they open it; left alone it
     * accumulates forever, which is the whole reason {@code sweepSearchResidue} had to be written.
     *
     * <p><b>It never reasons from absence.</b> Each page of index rows is checked against the tenant
     * directly — "of these five hundred ids, which do you still have?" — so a page is only ever compared
     * with an answer about that page. That is what makes a deadline-cut sweep harmless: the alternative
     * shape, "delete everything not in the set I collected", turns a cut run into a mass deletion of a
     * tenant's queue.
     *
     * @return where the next run resumes, or {@link #SCAN_START} when it reached the end
     */
    private UUID sweep(TenantHome home, UUID from, Instant deadline, TenantFanOut.Fleet fleet,
            Tally tally) {
        UUID cursor = from;
        while (clock.instant().isBefore(deadline)) {
            List<TicketIndex.Row> page = indexPage(home, cursor);
            if (page.isEmpty()) {
                return SCAN_START;
            }
            cursor = page.getLast().ticketId();
            List<UUID> candidates = page.stream()
                    .filter(row -> !home.pooled() || servedByThisHome(fleet, home, row.orgId()))
                    .map(TicketIndex.Row::ticketId)
                    .toList();
            if (!candidates.isEmpty()) {
                Set<UUID> live = Set.copyOf(liveAmong(candidates));
                List<UUID> residue = candidates.stream().filter(id -> !live.contains(id)).toList();
                tally.removed += index.forget(residue);
            }
            if (page.size() < PAGE) {
                return SCAN_START;
            }
        }
        return cursor;
    }

    /**
     * One page of the index's own rows for this home, in ticket-id order.
     *
     * <p><b>The pool takes no org predicate and every other home does, and that asymmetry is the point.</b>
     * A silo is one organization, so {@code org_id = ?} is exact and rides
     * {@code idx_ticket_index_org (org_id, ticket_id)}. The pool's organizations are not a set any table
     * holds — {@code TenantRoutes.readHome} answers {@code tenant_pool} for a tenant the registry has
     * never heard of — so its page is taken unfiltered and each row's home is decided by the router
     * afterwards. It costs the pool's pass one forward walk of a narrow index per night; inventing an
     * "is pooled" column instead would put placement data inside a projection, where it would drift the
     * first time a tenant was promoted.
     */
    private List<TicketIndex.Row> indexPage(TenantHome home, UUID after) {
        // Read on the PLATFORM axis through TicketIndex's own hop? No — this is a plain read of a
        // qualified table and the sweep is already inside the home's pin, which for a REMOTE home means
        // this statement would run against a database with no `platform.ticket_index` in it. So it takes
        // the platform axis explicitly, exactly like every other platform-tier statement in a fanned-out
        // visit (ADR 0011 §5, TenantHomeSweep's class note).
        return TenantContext.callAsPlatform(() -> home.pooled()
                ? jdbc.query("""
                        select ticket_id, org_id, opener_person_id, subject, category, priority, status,
                               assignee_person_id, escalated, first_response_at, resolution_due_at,
                               created_at
                          from platform.ticket_index
                         where ticket_id > ?
                         order by ticket_id
                         limit ?
                        """, ROW, after, PAGE)
                : jdbc.query("""
                        select ticket_id, org_id, opener_person_id, subject, category, priority, status,
                               assignee_person_id, escalated, first_response_at, resolution_due_at,
                               created_at
                          from platform.ticket_index
                         where org_id = ? and ticket_id > ?
                         order by ticket_id
                         limit ?
                        """, ROW, home.axis(), after, PAGE));
    }

    /**
     * Which of these ids the home still holds as a live ticket. One statement on the tenant's axis,
     * unqualified so the {@code search_path} places it, with the ids bound rather than interpolated.
     */
    private List<UUID> liveAmong(List<UUID> ticketIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ticketIds.size(), "?"));
        return jdbc.query("select id from ticket where deleted_at is null and id in (" + placeholders + ")",
                (rs, row) -> rs.getObject("id", UUID.class), ticketIds.toArray());
    }

    /**
     * Whether the router agrees that this organization's rows are in this home right now. An empty
     * answer means the fleet is withholding that tenant this pass — see the class note on why that has
     * to exclude it from BOTH arms rather than from the sweep alone.
     */
    private static boolean servedByThisHome(TenantFanOut.Fleet fleet, TenantHome home, UUID orgId) {
        return fleet.homeOf(orgId).filter(actual -> actual.schema().equals(home.schema())).isPresent();
    }

    /** One run's repair counts, carried across the homes because the log line is the RUN's. */
    private static final class Tally {
        private int refreshed;
        private int removed;
    }
}
