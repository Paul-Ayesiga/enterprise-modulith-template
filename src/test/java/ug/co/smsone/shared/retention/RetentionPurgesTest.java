package ug.co.smsone.shared.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two-pass retention orchestration, in isolation (no DB): non-overridden orgs purge at the
 * platform-default cutoff while the overridden orgs are excluded, then each overridden org purges at
 * ITS OWN cutoff. With no overrides it collapses to a single default-cutoff pass.
 *
 * <p>Since ADR 0010 Phase 5 one call is one tenant HOME rather than one installation, which is why
 * {@link RetentionPurges.Bounds} carries a deadline — the fan-out gives each home a slice of the run's
 * budget, and a home that spends its slice must hand the rest of the fleet what is left rather than the
 * lease. {@link #theOverridePassStopsAtTheDeadlineInsteadOfWalkingEveryOverriddenOrg()} is the half of
 * that no batch cap can express.
 */
class RetentionPurgesTest {

    /** A deadline no test pass can reach, for the cases that are not about the budget. */
    private static final RetentionPurges.Bounds UNBOUNDED =
            new RetentionPurges.Bounds(500, 100, Clock.systemUTC(), Instant.MAX);

    @Test
    void purgesEveryoneAtTheDefaultThenEachOverriddenOrgAtItsOwnCutoff() {
        UUID overridden = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        RetentionOverrides overrides = scope -> Map.of(overridden, 3650);

        List<Instant> excludingCutoffs = new ArrayList<>();
        List<Collection<UUID>> excludingExcludes = new ArrayList<>();
        List<Instant> perOrgCutoffs = new ArrayList<>();
        List<UUID> perOrgOrgs = new ArrayList<>();
        // Each purger deletes 0, so the drain loop stops after one batch per pass.
        RetentionPurges.ExcludingPurge excluding = (cutoff, exclude, batch) -> {
            excludingCutoffs.add(cutoff);
            excludingExcludes.add(new ArrayList<>(exclude));
            return 0;
        };
        RetentionPurges.OrgPurge perOrg = (cutoff, orgId, batch) -> {
            perOrgCutoffs.add(cutoff);
            perOrgOrgs.add(orgId);
            return 0;
        };

        RetentionPurges.purge(overrides, RetentionScope.EXCHANGE_JOB, now, Duration.ofDays(90),
                UNBOUNDED, excluding, perOrg);

        // Pass 1: the platform default (now − 90d), excluding the overridden org.
        assertThat(excludingCutoffs).containsExactly(now.minus(Duration.ofDays(90)));
        assertThat(excludingExcludes.get(0)).containsExactly(overridden);
        // Pass 2: the overridden org at its OWN cutoff (now − 3650d).
        assertThat(perOrgCutoffs).containsExactly(now.minus(Duration.ofDays(3650)));
        assertThat(perOrgOrgs).containsExactly(overridden);
    }

    @Test
    void withNoOverridesItIsASingleDefaultCutoffPass() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        RetentionOverrides none = scope -> Map.of();
        List<Collection<UUID>> excludes = new ArrayList<>();
        int[] perOrgCalls = {0};

        RetentionPurges.purge(none, RetentionScope.WEBHOOK_DELIVERY, now, Duration.ofDays(30), UNBOUNDED,
                (cutoff, exclude, batch) -> {
                    excludes.add(new ArrayList<>(exclude));
                    return 0;
                },
                (cutoff, orgId, batch) -> {
                    perOrgCalls[0]++;
                    return 0;
                });

        assertThat(excludes).hasSize(1);
        assertThat(excludes.get(0)).isEmpty();
        assertThat(perOrgCalls[0]).isZero();
    }

    /**
     * The bound {@code maxBatches} structurally cannot provide: the override pass is one drain per
     * overridden org, so a home with many overrides outlasts its slice on the strength of the LOOP while
     * every individual drain finishes instantly.
     *
     * <p>Without this, a fan-out over 200 homes would let home 1 spend the whole ShedLock lease and
     * every home behind it would go unpurged — with no exception, because each drain succeeded.
     */
    @Test
    void theOverridePassStopsAtTheDeadlineInsteadOfWalkingEveryOverriddenOrg() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        Map<UUID, Integer> manyOverrides = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            manyOverrides.put(UUID.randomUUID(), 10);
        }
        // Reads: one before each org in the loop. The third read is past the deadline, so two orgs are
        // visited and the remaining three are left for the next run.
        SteppingClock clock = new SteppingClock(now, Duration.ofMinutes(1));
        RetentionPurges.Bounds bounds =
                new RetentionPurges.Bounds(500, 100, clock, now.plus(Duration.ofMinutes(2)));

        List<UUID> visited = new ArrayList<>();
        RetentionPurges.purge(scope -> manyOverrides, RetentionScope.EXCHANGE_JOB, now, Duration.ofDays(30),
                bounds,
                (cutoff, exclude, batch) -> 0,
                (cutoff, orgId, batch) -> {
                    visited.add(orgId);
                    return 0;
                });

        assertThat(visited)
                .as("the pass must stop at its slice of the run's budget, not walk every overridden org")
                .hasSizeLessThan(manyOverrides.size());
        assertThat(visited)
                .as("and it must do SOME work — a deadline checked before the first org would make "
                        + "'we visited this home' stop meaning anything")
                .isNotEmpty();
    }

    /** Advances by a fixed step on every read, so a deadline arrives after a known number of checks. */
    private static final class SteppingClock extends Clock {

        private final Instant start;
        private final Duration step;
        private int reads;

        private SteppingClock(Instant start, Duration step) {
            this.start = start;
            this.step = step;
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
