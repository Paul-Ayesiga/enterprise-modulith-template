package ug.co.smsone.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fan-out loop's own contract, with no database in it (ADR 0010 §3.4, Phase 5).
 *
 * <p>Deliberately not an integration test, and that is not a shortcut. What is under test is the
 * ROTATION — which homes a run visits, in what order, with what budget, and where the next run picks up
 * — and every one of those is decided from a list, a clock and a field. Proving it against real schemas
 * would cost 200–330 ms of Flyway per home and would make the interesting cases (a run cut at home two,
 * a permanently broken silo) reachable only by luck. The half the database DOES decide — that a home's
 * work lands in that home's schema — is proved per job, on real containers, by the fan-out tests in
 * {@code scheduler}, {@code support}, {@code webhooks} and {@code subscription}.
 */
class TenantHomeSweepTest {

    @BeforeEach
    void startWithNoAxis() {
        // JUnit reuses the platform thread across classes, so an integration class that ran before this
        // one may have left a pin. Clearing first is what makes the assertion below about THIS class.
        TenantContext.clear();
    }

    @AfterEach
    void theThreadIsLeftWithoutAnAxis() {
        assertThat(TenantContext.current())
                .as("a sweep that leaked a pin would hand the next unit of work on this pooled thread "
                        + "someone else's schema — the failure ADR 0010 §3.2 calls the worst this design "
                        + "can produce")
                .isEqualTo(Tenant.ABSENT);
    }

    @Test
    void visitsThePoolAndEverySiloOnItsOwnAxis() {
        List<TenantHome> homes = fleet(2);
        List<String> visited = new ArrayList<>();
        List<Tenant> axes = new ArrayList<>();

        new TenantHomeSweep("test").over(homes, Clock.systemUTC(), farFuture(), (home, deadline) -> {
            visited.add(home.schema());
            axes.add(TenantContext.current());
        });

        assertThat(visited).containsExactlyElementsOf(homes.stream().map(TenantHome::schema).toList());
        assertThat(axes)
                .as("each home's work runs on THAT home's axis — a loop that pinned once and iterated "
                        + "inside would write every home's rows into the first home's schema")
                .containsExactlyElementsOf(homes.stream().map(home -> Tenant.of(home.axis())).toList());
    }

    /**
     * The bug the whole cursor exists for: a run cut part way that restarted at the head would re-do the
     * same prefix every time and never reach the tail — silently, with a healthy log line, forever.
     *
     * <p>The budget is arranged so run 1 covers the pool and the FIRST silo and stops before the second.
     * A sweep that restarted the rotation would then spend run 2 on the first silo again; one that
     * resumes spends it on the second, and only the second assertion can tell those apart.
     */
    @Test
    void aRunCutByItsDeadlineResumesAfterTheHomeItStoppedOnRatherThanAtTheHead() {
        List<TenantHome> homes = fleet(4); // pool + four silos
        TenantHomeSweep sweep = new TenantHomeSweep("test");
        SteppingClock clock = new SteppingClock(Duration.ofMinutes(1));

        List<String> first = new ArrayList<>();
        TenantHomeSweep.Swept cut = sweep.over(homes, clock, clock.start().plus(Duration.ofMinutes(4)),
                (home, deadline) -> first.add(home.schema()));

        assertThat(cut.cutShort()).isTrue();
        assertThat(first).containsExactly(homes.get(0).schema(), homes.get(1).schema());

        List<String> second = new ArrayList<>();
        clock.restart();
        sweep.over(homes, clock, clock.start().plus(Duration.ofMinutes(4)),
                (home, deadline) -> second.add(home.schema()));

        assertThat(second)
                .as("run 2 must reach the pool and the SECOND silo. A sweep that restarted the silo "
                        + "rotation at the head would visit the first silo again and the fourth would "
                        + "never be swept at all")
                .containsExactly(homes.get(0).schema(), homes.get(2).schema());
    }

    /**
     * {@code SoftDeletePurgeJob}'s doctrine, applied to the fan-out: one unreachable schema must not
     * cost every home behind it its sweep, and the run must still fail.
     */
    @Test
    void oneHomeThatThrowsDoesNotStopTheRestAndTheRunStillFails() {
        List<TenantHome> homes = fleet(2);
        List<String> visited = new ArrayList<>();

        TenantHomeSweep.Swept swept = new TenantHomeSweep("test")
                .over(homes, Clock.systemUTC(), farFuture(), (home, deadline) -> {
                    visited.add(home.schema());
                    if (home.schema().equals(homes.get(1).schema())) {
                        throw new IllegalStateException("relation \"ticket\" does not exist");
                    }
                });

        assertThat(visited).as("every home is still visited").hasSize(homes.size());
        assertThatThrownBy(swept::rethrowFirstFailure)
                .as("loud AND complete: continuing must not turn into swallowing")
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A permanently broken silo must not park the rotation on itself. The cursor advances BEFORE the
     * attempt, so the next run continues past it — otherwise one unreachable schema starves every home
     * behind it, which converts a single loud failure into a silent fleet-wide one.
     */
    @Test
    void theRotationAdvancesPastAHomeThatFailedRatherThanRetryingItForever() {
        List<TenantHome> homes = fleet(2);
        TenantHomeSweep sweep = new TenantHomeSweep("test");
        SteppingClock clock = new SteppingClock(Duration.ofMinutes(1));

        sweep.over(homes, clock, clock.start().plus(Duration.ofMinutes(4)), (home, deadline) -> {
            if (home.schema().equals(homes.get(1).schema())) {
                throw new IllegalStateException("unreachable schema");
            }
        });

        List<String> next = new ArrayList<>();
        clock.restart();
        sweep.over(homes, clock, clock.start().plus(Duration.ofMinutes(4)),
                (home, deadline) -> next.add(home.schema()));

        assertThat(next)
                .as("the run after a failure continues at the NEXT home; parking on the broken one "
                        + "would starve every home behind it for as long as it stayed broken")
                .containsExactly(homes.get(0).schema(), homes.get(2).schema());
    }

    /**
     * With nothing siloed — every installation until the first promotion — the pool must keep the WHOLE
     * budget. Halving it would have been a regression dressed as fairness: the reserved half would
     * protect nobody, and every nightly purge would have lost half its deadline the day this class
     * landed.
     */
    @Test
    void withNoSilosThePoolKeepsTheWholeBudget() {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(30));
        List<Instant> budgets = new ArrayList<>();

        new TenantHomeSweep("test").over(List.of(TenantHome.pool()), Clock.systemUTC(), deadline,
                (home, homeDeadline) -> budgets.add(homeDeadline));

        assertThat(budgets).hasSize(1);
        assertThat(budgets.getFirst()).isCloseTo(deadline, within(2L, ChronoUnit.SECONDS));
    }

    /**
     * The pool is not one home among many for budgeting: it holds every tenant nobody has promoted —
     * thousands, against a ceiling of two hundred — so an equal slice would starve the fleet to serve
     * the exception.
     */
    @Test
    void thePoolGetsHalfTheBudgetAndTheSilosShareTheRest() {
        List<TenantHome> homes = fleet(3);
        Instant start = Instant.now();
        Instant deadline = start.plus(Duration.ofMinutes(30));
        List<Instant> budgets = new ArrayList<>();

        new TenantHomeSweep("test").over(homes, Clock.fixed(start, ZoneOffset.UTC), deadline,
                (home, homeDeadline) -> budgets.add(homeDeadline));

        assertThat(Duration.between(start, budgets.getFirst()).toMinutes())
                .as("half of thirty for the pool").isEqualTo(15);
        assertThat(Duration.between(start, budgets.get(1)).toMinutes())
                .as("a third of what is LEFT for the first silo — the clock is frozen here, so the "
                        + "pool's unspent half is handed on rather than expiring")
                .isEqualTo(10);
    }

    /**
     * A fleet with no servable home is what a promotion produces on an installation that has no silos
     * yet: the pool stands down and there is nowhere to sweep. It must be a no-op that reports itself,
     * not a crash and not a silent success.
     */
    @Test
    void anEmptyFleetIsANoOpThatReportsItselfCutShort() {
        TenantHomeSweep.Swept swept = new TenantHomeSweep("test")
                .over(List.of(), Clock.systemUTC(), farFuture(), (home, deadline) -> {
                    throw new AssertionError("there was nothing to visit");
                });

        assertThat(swept.visited()).isZero();
        assertThat(swept.cutShort()).isTrue();
        assertThat(swept.firstFailure()).isNull();
    }

    private static Instant farFuture() {
        return Instant.now().plus(Duration.ofHours(1));
    }

    /**
     * The pool plus {@code silos} silos, in the order {@code TenantFanOut} hands them over — pool first,
     * silos by schema name. Matching that ordering here is what makes the resume assertions mean the
     * same thing they will mean in production.
     */
    private static List<TenantHome> fleet(int silos) {
        List<TenantHome> ordered = new ArrayList<>();
        for (int i = 0; i < silos; i++) {
            UUID orgId = UUID.randomUUID();
            ordered.add(TenantHome.silo(orgId, TenantSchemas.siloSchema(orgId)));
        }
        ordered.sort(Comparator.comparing(TenantHome::schema));
        List<TenantHome> homes = new ArrayList<>();
        homes.add(TenantHome.pool());
        homes.addAll(ordered);
        return List.copyOf(homes);
    }

    /** Advances a fixed step on every read, so a deadline arrives after a known number of checks. */
    private static final class SteppingClock extends Clock {

        private final Duration step;
        private Instant start = Instant.now();
        private int reads;

        private SteppingClock(Duration step) {
            this.step = step;
        }

        private Instant start() {
            return start;
        }

        private void restart() {
            start = Instant.now();
            reads = 0;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return start.plus(step.multipliedBy(reads++));
        }
    }
}
