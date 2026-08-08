package ug.co.smsone.shared.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * {@code platform.queue_signal} as a primitive, away from any one queue: what a claim may touch, and
 * what a release is allowed to overwrite. The three durable queues all reach the table through this
 * class, so a defect here is a defect in all of them at once — which is how the one below arrived.
 *
 * <p><b>The queue name is synthetic on purpose.</b> {@code QUEUE} is a plain string parameter, so these
 * tests use one no worker polls; nothing here can be claimed out from under an assertion by a real
 * drain, and nothing here can strand a real one. The rows are platform-tier, so the harness's PLATFORM
 * pin is the right axis and no {@code runAs} is needed.
 */
class QueueSignalsTest extends AbstractIntegrationTest {

    /** A queue no worker polls — see the class note. */
    private static final String QUEUE = "test-signal-primitive";

    private static final Duration LEASE = Duration.ofMinutes(2);

    @Autowired
    private QueueSignals signals;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetSignals() {
        jdbc.update("delete from platform.queue_signal where queue = ?", QUEUE);
    }

    /**
     * Puts the optimizer's picture of {@code queue_signal} back where it found it. Only
     * {@link #aClaimLeasesOneTenantHoweverManyAreDue} disturbs it, and only statistics — but a table
     * the planner still believes is two thousand rows of unique queues is a confusing thing to hand
     * the next class, so it is handed back analyzed.
     */
    @AfterEach
    void restoreStatistics() {
        jdbc.execute("analyze platform.queue_signal");
    }

    /**
     * <b>A claim leases ONE tenant, however many are due — and this is the guarantee the whole table
     * exists to provide.</b> One signal row per tenant only bounds a batch to one tenant if the claim
     * that hands the tenant over is itself bounded; a claim that leases five and returns one has
     * removed four tenants from the queue for a whole stale-lock window with no worker holding them,
     * which is the starvation ADR 0010 §2.1 set out to make structurally impossible, reintroduced
     * inside the mechanism meant to prevent it.
     *
     * <p><b>It was.</b> The claim was written {@code update … from (select … limit 1 for update skip
     * locked) c where s.org_id = c.org_id}, and there the candidate is an ordinary join input: a nested
     * loop may RESCAN it once per outer row, {@code LockRows} skips the row the same command already
     * updated, and each rescan therefore hands back the NEXT due tenant. The number of tenants a claim
     * leased was whatever the planner chose that execution — one when the candidate drove the join,
     * all of them when the signal table did. Three fairness tests failed against it in one suite run
     * and passed in the next, with the same code.
     *
     * <p><b>The fixture below is what makes this test fail without the fix, and it is not a trick.</b>
     * The join order flips on one estimate: how many rows the planner thinks {@code s.queue = ?}
     * matches. Told "about one", it puts the signal table on the OUTSIDE and rescans the candidate
     * once per row; told "many", it drives from the candidate and the defect is invisible. So the test
     * states the estimate it needs — {@code analyze} over a table of one row per queue, which is what
     * this table genuinely looks like almost all of the time — instead of hoping the suite's churn
     * happens to produce it, which is precisely what made the failure come and go across runs.
     *
     * <p><b>And it repeats, because a planner choice is not a constant.</b> Even with the estimate in
     * place the broken claim leases one tenant some of the time; twelve rounds turn "usually fails"
     * into "fails", while asserting the same single fact each round. The fix — a materialized CTE,
     * evaluated exactly once whatever the plan — makes every round pass by construction rather than by
     * luck, which is the difference this test is really pinning.
     */
    @Test
    void aClaimLeasesOneTenantHoweverManyAreDue() {
        plannerBelievesAboutOneRowPerQueue();
        for (int round = 0; round < 12; round++) {
            resetSignals();
            List<UUID> due = seedDue(4);

            QueueSignals.Leased leased = signals.claim(QUEUE, LEASE).orElseThrow();

            assertThat(leasedScopes())
                    .as("round %d: one claim, one leased tenant — the rest are nobody's", round)
                    .containsExactly(leased.scope());
            assertThat(claimableNow())
                    .as("round %d: every tenant this claim did not take is still claimable NOW", round)
                    .containsExactlyInAnyOrderElementsOf(
                            due.stream().filter(scope -> !scope.equals(leased.scope())).toList());
        }
    }

    /** Oldest-waiting first, which is the ordering the {@code (queue, due_at)} index exists to serve. */
    @Test
    void theLongestWaitingDueTenantIsTheOneClaimed() {
        List<UUID> due = seedDue(3); // seeded 3, 2 and 1 hours late, in that order

        assertThat(signals.claim(QUEUE, LEASE).orElseThrow().scope()).isEqualTo(due.get(0));
        assertThat(signals.claim(QUEUE, LEASE).orElseThrow().scope()).isEqualTo(due.get(1));
    }

    /** A tenant nobody is due to work on is not claimable, which is what makes the lease a lease. */
    @Test
    void aLeasedTenantIsNotClaimableAgainUntilItsLeaseExpires() {
        UUID scope = seedDue(1).get(0);
        signals.claim(QUEUE, LEASE).orElseThrow();

        assertThat(signals.claim(QUEUE, LEASE)).as("its lease has not expired").isEmpty();

        // The crash path: the worker never came back, and the row's own due_at is what recovers it.
        jdbc.update("update platform.queue_signal set due_at = now() - interval '1 second' "
                + "where queue = ? and org_id = ?", QUEUE, scope);
        assertThat(signals.claim(QUEUE, LEASE).orElseThrow().scope())
                .as("an expired lease is claimable by anyone, holder or not")
                .isEqualTo(scope);
    }

    /**
     * <b>A release from a worker that no longer holds the tenant must do nothing.</b> The second claim
     * below is another worker taking over after the first lease expired; if the first worker's release
     * still landed it would overwrite the new holder's {@code due_at} with a time computed from rows
     * the new holder has since claimed — the tenant would be handed out twice and then parked.
     */
    @Test
    void aReleaseFromASupersededLeaseIsANoOp() {
        UUID scope = seedDue(1).get(0);
        QueueSignals.Leased first = signals.claim(QUEUE, LEASE).orElseThrow();
        expire(scope);
        QueueSignals.Leased second = signals.claim(QUEUE, LEASE).orElseThrow();

        signals.release(QUEUE, scope, first.lease(), Instant.now().plus(Duration.ofDays(1)));

        assertThat(leaseOf(scope)).as("the row still belongs to the second claim").isEqualTo(second.lease());
        assertThat(claimableNow()).as("and the first worker's due_at was not written").isEmpty();
    }

    /** The same fence on the delete arm: a superseded worker must not delete a tenant's signal. */
    @Test
    void aDeletingReleaseFromASupersededLeaseIsANoOp() {
        UUID scope = seedDue(1).get(0);
        QueueSignals.Leased first = signals.claim(QUEUE, LEASE).orElseThrow();
        expire(scope);
        signals.claim(QUEUE, LEASE).orElseThrow();

        signals.release(QUEUE, scope, first.lease(), null);

        assertThat(signalCount()).as("the signal survives a stale worker deciding it was empty").isEqualTo(1);
    }

    /**
     * <b>An enqueue during a batch wins over the release that follows it,</b> and this is the case the
     * fence is really for. The releasing worker computed its {@code due_at} from the rows it could see;
     * the rows this raise announces are not among them, so letting that release land would park a
     * tenant that has work waiting right now — queued, unclaimable, and with nothing left to announce
     * it a second time. Raising voids the lease, so the release can only be a no-op.
     */
    @Test
    void anEnqueueDuringABatchVoidsTheLeaseSoTheReleaseCannotBuryTheNewWork() {
        UUID scope = seedDue(1).get(0);
        QueueSignals.Leased leased = signals.claim(QUEUE, LEASE).orElseThrow();

        signals.raise(QUEUE, scope); // the enqueue that landed mid-batch
        signals.release(QUEUE, scope, leased.lease(), Instant.now().plus(Duration.ofHours(1)));

        assertThat(claimableNow())
                .as("the newly enqueued work is claimable now, not in an hour")
                .containsExactly(scope);
    }

    /** Releasing a tenant with nothing left deletes its signal — the table only holds live work. */
    @Test
    void releasingATenantWithNothingLeftDeletesItsSignal() {
        UUID scope = seedDue(1).get(0);
        QueueSignals.Leased leased = signals.claim(QUEUE, LEASE).orElseThrow();

        signals.release(QUEUE, scope, leased.lease(), null);

        assertThat(signalCount()).isZero();
    }

    /** A raise can only ever pull a tenant's turn forward, never push it back. */
    @Test
    void aRaiseNeverMovesATenantLater() {
        UUID scope = seedDue(1).get(0);
        Instant before = dueOf(scope);

        signals.raise(QUEUE, scope);

        assertThat(dueOf(scope)).as("least(existing, now()), so the earlier time stands").isEqualTo(before);
    }

    // ---- fixture ----

    /**
     * {@code count} tenants with work, the first the longest-waiting. Raised through the real
     * {@link QueueSignals#raise} and then aged, so the rows are the ones production writes.
     */
    private List<UUID> seedDue(int count) {
        List<UUID> scopes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID scope = UUID.randomUUID();
            signals.raise(QUEUE, scope);
            jdbc.update("update platform.queue_signal set due_at = now() - (? * interval '1 hour') "
                    + "where queue = ? and org_id = ?", count - i, QUEUE, scope);
            scopes.add(scope);
        }
        return scopes;
    }

    /**
     * Teaches the optimizer that a queue is worth about one row — the estimate that decides the claim's
     * join order, and the state this table is in whenever it is not backed up. Written with rows of its
     * own under names no queue uses, so nothing real is touched, and only the statistics outlive them;
     * {@link #restoreStatistics()} puts those back.
     */
    private void plannerBelievesAboutOneRowPerQueue() {
        jdbc.update("insert into platform.queue_signal (queue, org_id, due_at) "
                + "select 'stats-probe-' || g, gen_random_uuid(), now() from generate_series(1, 2000) g");
        jdbc.execute("analyze platform.queue_signal");
        jdbc.update("delete from platform.queue_signal where queue like 'stats-probe-%'");
    }

    private void expire(UUID scope) {
        jdbc.update("update platform.queue_signal set due_at = now() - interval '1 second' "
                + "where queue = ? and org_id = ?", QUEUE, scope);
    }

    private List<UUID> claimableNow() {
        return jdbc.queryForList("select org_id from platform.queue_signal "
                + "where queue = ? and due_at <= now() order by due_at", UUID.class, QUEUE);
    }

    private List<UUID> leasedScopes() {
        return jdbc.queryForList("select org_id from platform.queue_signal "
                + "where queue = ? and lease is not null", UUID.class, QUEUE);
    }

    private UUID leaseOf(UUID scope) {
        return jdbc.queryForObject("select lease from platform.queue_signal where queue = ? and org_id = ?",
                UUID.class, QUEUE, scope);
    }

    private Instant dueOf(UUID scope) {
        return jdbc.queryForObject("select due_at from platform.queue_signal where queue = ? and org_id = ?",
                Timestamp.class, QUEUE, scope).toInstant();
    }

    private int signalCount() {
        return jdbc.queryForObject("select count(*) from platform.queue_signal where queue = ?",
                Integer.class, QUEUE);
    }
}
