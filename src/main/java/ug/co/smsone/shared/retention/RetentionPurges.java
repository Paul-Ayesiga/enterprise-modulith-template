package ug.co.smsone.shared.retention;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Two-pass retention purge honoring per-org overrides: everyone at the platform-default cutoff
 * EXCEPT orgs with an override, then each overridden org at ITS OWN cutoff. An override IS that
 * org's retention — it may keep a row longer OR shorter than the default. Extracted here so every
 * org-scoped retention job (webhook deliveries, exchange jobs) purges the same way; each job supplies
 * its own two batch-deletes (one that excludes a set of orgs, one scoped to a single org).
 *
 * <p><b>One call is now one HOME, not one installation</b> (ADR 0010 Phase 5). {@code
 * org_retention_override} is tenant-tier, so the overrides this reads are the overrides of whichever
 * schema the caller pinned, and the jobs call it once per home. That is why {@link Bounds} carries a
 * deadline: without one, a home with a large backlog spends the whole ShedLock lease and every home
 * behind it goes unpurged, which for a retention promise is indistinguishable from the job not running.
 */
public final class RetentionPurges {

    /** Delete up to {@code batchSize} terminal rows older than {@code cutoff}, skipping {@code excludeOrgs}. */
    @FunctionalInterface
    public interface ExcludingPurge {
        int purge(Instant cutoff, Collection<UUID> excludeOrgs, int batchSize);
    }

    /** Delete up to {@code batchSize} of one org's terminal rows older than {@code cutoff}. */
    @FunctionalInterface
    public interface OrgPurge {
        int purge(Instant cutoff, UUID orgId, int batchSize);
    }

    /**
     * What bounds one home's purge: how big a batch, how many of them, and when to stop regardless.
     *
     * <p><b>{@code maxBatches} and {@code deadline} bound different things and neither replaces the
     * other.</b> The batch cap bounds ONE pass, so a single enormous table cannot monopolise a run; the
     * deadline bounds the WHOLE home, which is what the second pass needs — that pass is one drain per
     * overridden org, so its cost is O(orgs with an override) and no per-pass cap can see the total.
     *
     * @param clock the run's clock, so a test can make the deadline arrive without waiting
     */
    public record Bounds(int batchSize, int maxBatches, Clock clock, Instant deadline) {

        /** True once this home has spent its slice; checked between batches and between orgs. */
        boolean spent() {
            return !clock.instant().isBefore(deadline);
        }
    }

    private RetentionPurges() {
    }

    /**
     * Runs the two passes over the caller's current home and returns the total deleted.
     *
     * <p>A pass cut by the deadline leaves its remainder for the next run, and nothing is lost by that:
     * every batch deletes rows past a cutoff, so the untouched remainder is simply the next run's head.
     * That is the whole reason these jobs need no positional cursor of their own — the delete IS the
     * cursor — and it is what makes stopping mid-home safe rather than merely tolerable.
     */
    public static int purge(RetentionOverrides overrides, String scope, Instant now,
            Duration defaultRetention, Bounds bounds, ExcludingPurge excluding, OrgPurge perOrg) {
        Map<UUID, Integer> byOrg = overrides.daysByScope(scope);
        Instant defaultCutoff = now.minus(defaultRetention);
        int total = drain(bounds, size -> excluding.purge(defaultCutoff, byOrg.keySet(), size));
        for (Map.Entry<UUID, Integer> entry : byOrg.entrySet()) {
            if (bounds.spent()) {
                // Checked BETWEEN orgs and not only between batches: the override pass is one drain per
                // org, so a home with two hundred overridden orgs can outlast a lease on the strength of
                // the loop alone, with every individual drain finishing well inside it.
                return total;
            }
            Instant orgCutoff = now.minus(Duration.ofDays(entry.getValue()));
            total += drain(bounds, size -> perOrg.purge(orgCutoff, entry.getKey(), size));
        }
        return total;
    }

    private interface Batch {
        int purge(int batchSize);
    }

    private static int drain(Bounds bounds, Batch batch) {
        int total = 0;
        for (int i = 0; i < bounds.maxBatches(); i++) {
            // Never before the first batch, so every pass this home reaches does at least some work —
            // the same rule SoftDeletePurgeJob.purgeTable follows, and for the same reason: a deadline
            // that could skip a pass entirely would make "we visited this home" stop meaning anything.
            if (i > 0 && bounds.spent()) {
                return total;
            }
            int deleted = batch.purge(bounds.batchSize());
            total += deleted;
            if (deleted < bounds.batchSize()) {
                break;
            }
        }
        return total;
    }
}
