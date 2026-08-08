package ug.co.smsone.shared.tenancy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The loop every fanned-out job runs: visit each {@link TenantHome} on its own axis, inside a budget,
 * resuming next run where this one was cut (ADR 0010 §3.4, §5, Phase 5).
 *
 * <p>One instance per job, held as a field, because the resume position is the job's and has to
 * survive between runs. It is not a bean: two jobs sharing a cursor would each move the other's.
 *
 * <h2>The five properties, and why none of them is optional</h2>
 *
 * <ol>
 *   <li><strong>The pin.</strong> {@code TenantContext.runAs(home.axis(), …)} wraps each visit, so the
 *       schema is chosen when the visit's first connection is borrowed. It is outside the visit and not
 *       inside it because the pin must happen before any transaction opens — {@code TenantContext.set}
 *       throws otherwise, deliberately (ADR 0010 §3.2).</li>
 *   <li><strong>A deadline per home, not just per run.</strong> A run deadline alone lets the first
 *       home consume all of it, which is the failure {@code SoftDeletePurgeJob.PLATFORM_BUDGET} already
 *       had to invent a second constant for. Here the remaining budget is divided by the remaining
 *       homes and recomputed after every one, so a home that finishes early hands its slack straight to
 *       the next rather than expiring it.</li>
 *   <li><strong>The pool gets its own half.</strong> Not an equal share: {@code tenant_pool} holds
 *       every tenant nobody has promoted — thousands, against a silo ceiling of 200 — so slicing the
 *       budget evenly across homes would starve the overwhelming majority of the fleet to serve the
 *       exception. Half is a floor under each side, the same shape and the same argument as
 *       {@code PLATFORM_BUDGET}: two halves whose costs grow differently must not share one budget. An
 *       early-finishing pool hands its remainder to the silos, because every deadline here is absolute.</li>
 *   <li><strong>A resumable cursor over the silos.</strong> A run cut at silo 40 of 200 that restarted
 *       at silo 1 the next night would never reach the tail, and the tail's retention, escalation or
 *       billing would simply never happen while the log said the job ran. The cursor remembers a SCHEMA
 *       NAME rather than an index, so a silo created or dropped between runs shifts nothing.</li>
 *   <li><strong>Failure is per home and the run still fails.</strong> {@code SoftDeletePurgeJob}'s
 *       doctrine verbatim — <em>loud AND complete, not loud instead of complete</em>. One unreachable
 *       schema must not cost every home behind it its sweep, and the cursor advances PAST a home that
 *       failed so a permanently broken silo cannot park the rotation on itself.</li>
 * </ol>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not cap the number of homes. Truncating the list would silently stop doing the last
 * tenant's work — the exact bug the fan-out exists to remove — where a deadline plus a cursor turns the
 * same pressure into a slower rotation that still reaches everyone. The count ceiling lives in the
 * promoter, where refusing has an operator behind it (ADR 0010 §8 Q1).
 *
 * <p>It does not persist the cursor. In memory means it survives across runs inside one process — a pod
 * lives for days — resets to the head on restart (safe: every visit is idempotent, so redoing a prefix
 * costs time and nothing else), and is not shared between replicas, so when the ShedLock winner
 * alternates the two rotations overlap rather than starve. Overlap is waste; starvation is data loss.
 * The durable version belongs in a {@code platform} table beside {@code tenant_placement} and needs a
 * migration this slice does not own — the same trade {@code SoftDeletePurgeJob.resumeAt} and
 * {@code OrgMembershipIndexReconciler.resumeFrom} already state.
 */
public final class TenantHomeSweep {

    private static final Logger log = LoggerFactory.getLogger(TenantHomeSweep.class);

    /**
     * The share of a run's remaining budget {@code tenant_pool} may spend before the silos get theirs.
     * See property 3 above; two is a floor under each side, not a measurement, and the property that
     * matters is that a floor exists at all.
     */
    private static final int POOL_BUDGET_DIVISOR = 2;

    private final String job;

    /**
     * The silo this job finished with, or {@code null} when the last run reached the end of the
     * rotation. Volatile, not because two runs overlap — ShedLock stops that — but because consecutive
     * runs land on whichever scheduler thread is free, and a plain field written by one and read by
     * another a night later has no happens-before edge between them. The failure would be silent and
     * would look exactly like the bug the cursor removes.
     */
    private volatile String resumeAfter;

    public TenantHomeSweep(String job) {
        this.job = job;
    }

    /** One home's work, on an axis the sweep has already pinned. */
    @FunctionalInterface
    public interface HomeWork {

        /**
         * @param home which schema this visit is for — for log lines and for a job that keys its own
         *     per-home state, never for building a schema-qualified name (the {@code search_path} does
         *     that)
         * @param deadline when this home must stop, so a job whose per-home work is itself a loop can
         *     hand the budget down instead of running to the end of the run's
         */
        void visit(TenantHome home, Instant deadline);
    }

    /** What one rotation did. The caller decides what to do with {@link #firstFailure}. */
    public record Swept(int visited, boolean cutShort, RuntimeException firstFailure) {

        /** Rethrows the first per-home failure, if any — the {@code loud AND complete} half. */
        public void rethrowFirstFailure() {
            if (firstFailure != null) {
                throw firstFailure;
            }
        }
    }

    /**
     * Visits every home once, starting at the pool and then continuing the silo rotation where the last
     * run left it.
     *
     * @param homes {@code TenantFanOut.Fleet.homes()} — pool first (or absent, while a promotion has it
     *     standing down), then silos in a stable order
     * @param deadline when the whole run must stop, derived by the caller from its ShedLock lease
     */
    public Swept over(List<TenantHome> homes, Clock clock, Instant deadline, HomeWork work) {
        if (homes.isEmpty()) {
            // Not a no-op worth hiding: an empty fleet means the pool stood down for a promotion and
            // nothing is siloed yet, so this run does nothing at all and the next one does it instead.
            log.info("{}: no tenant home is servable this run — a promotion has the pool standing down", job);
            return new Swept(0, true, null);
        }
        List<TenantHome> order = rotate(homes);
        int visited = 0;
        RuntimeException firstFailure = null;
        for (int index = 0; index < order.size(); index++) {
            TenantHome home = order.get(index);
            if (!clock.instant().isBefore(deadline)) {
                log.warn("{} stopped at its deadline after {} of {} tenant homes; the next run RESUMES "
                                + "after {} rather than restarting at the head, so the homes behind it are "
                                + "not starved. A run that hits this regularly has outgrown one pass — "
                                + "measure a home and re-derive the deadline and the lease together.",
                        job, visited, order.size(), resumeAfter);
                return new Swept(visited, true, firstFailure);
            }
            // Marked before the attempt, so a permanently failing home cannot park the rotation on
            // itself and starve every home behind it. Same rule as OrgMembershipIndexReconciler's.
            if (!home.pooled()) {
                resumeAfter = home.schema();
            }
            visited++;
            try {
                Instant homeDeadline = budgetFor(home, order, index, clock, deadline);
                TenantContext.runAs(home.axis(), () -> work.visit(home, homeDeadline));
            } catch (RuntimeException failure) {
                log.error("{} failed on tenant home {} (continuing with the remaining homes)",
                        job, home.schema(), failure);
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
        }
        // Reached the end of the rotation: the next run starts from the head again.
        resumeAfter = null;
        return new Swept(visited, false, firstFailure);
    }

    /**
     * The pool first, then the silos starting after the one the last run finished with, wrapping.
     *
     * <p>The pool is never rotated past. It holds every unpromoted tenant, so a rotation that could
     * leave it until the tail would defer the whole fleet's work to serve at most two hundred tenants
     * — see property 3 on the class.
     *
     * <p>A cursor naming a silo that no longer exists (demoted, dropped) simply matches nothing and the
     * rotation starts at the head, which is the safe direction: every visit is idempotent, so redoing a
     * prefix costs time where skipping a suffix costs the work.
     */
    private List<TenantHome> rotate(List<TenantHome> homes) {
        List<TenantHome> pooled = homes.stream().filter(TenantHome::pooled).toList();
        List<TenantHome> silos = homes.stream().filter(home -> !home.pooled()).toList();
        String after = resumeAfter;
        int start = 0;
        if (after != null) {
            for (int i = 0; i < silos.size(); i++) {
                if (silos.get(i).schema().equals(after)) {
                    start = i + 1;
                    break;
                }
            }
        }
        List<TenantHome> order = new ArrayList<>(pooled);
        for (int i = 0; i < silos.size(); i++) {
            order.add(silos.get((start + i) % silos.size()));
        }
        return order;
    }

    /**
     * How long this home may take: half of what is left for the pool, an equal share of what is left
     * for a silo. Recomputed from {@code now} at every home, which is what makes an early finish a gift
     * to the next home rather than time that expires unspent.
     *
     * <p><strong>The pool keeps the WHOLE budget when nothing is siloed</strong>, which is every
     * installation until the first promotion. Halving it there would be a regression dressed as
     * fairness: the reserved half would protect nobody and every nightly purge would lose half its
     * deadline the day this class landed.
     */
    private static Instant budgetFor(TenantHome home, List<TenantHome> order, int index, Clock clock,
            Instant deadline) {
        Instant now = clock.instant();
        long remainingMillis = Math.max(0, deadline.toEpochMilli() - now.toEpochMilli());
        int remainingHomes = order.size() - index;
        if (home.pooled()) {
            return now.plusMillis(remainingHomes == 1 ? remainingMillis
                    : remainingMillis / POOL_BUDGET_DIVISOR);
        }
        return now.plusMillis(remainingMillis / remainingHomes);
    }
}
