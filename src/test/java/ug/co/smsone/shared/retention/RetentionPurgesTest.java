package ug.co.smsone.shared.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two-pass retention orchestration, in isolation (no DB): non-overridden orgs purge at the
 * platform-default cutoff while the overridden orgs are excluded, then each overridden org purges at
 * ITS OWN cutoff. With no overrides it collapses to a single default-cutoff pass.
 */
class RetentionPurgesTest {

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
                500, 100, excluding, perOrg);

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

        RetentionPurges.purge(none, RetentionScope.WEBHOOK_DELIVERY, now, Duration.ofDays(30), 500, 100,
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
}
