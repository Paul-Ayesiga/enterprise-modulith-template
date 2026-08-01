package ug.co.smsone.shared.retention;

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

    private RetentionPurges() {
    }

    /**
     * Runs the two passes and returns the total deleted. {@code maxBatches} bounds EACH pass inside
     * the caller's lock lease, matching the single-pass jobs this replaces.
     */
    public static int purge(RetentionOverrides overrides, String scope, Instant now,
            Duration defaultRetention, int batchSize, int maxBatches,
            ExcludingPurge excluding, OrgPurge perOrg) {
        Map<UUID, Integer> byOrg = overrides.daysByScope(scope);
        Instant defaultCutoff = now.minus(defaultRetention);
        int total = drain(maxBatches, batchSize, size -> excluding.purge(defaultCutoff, byOrg.keySet(), size));
        for (Map.Entry<UUID, Integer> entry : byOrg.entrySet()) {
            Instant orgCutoff = now.minus(Duration.ofDays(entry.getValue()));
            total += drain(maxBatches, batchSize, size -> perOrg.purge(orgCutoff, entry.getKey(), size));
        }
        return total;
    }

    private interface Batch {
        int purge(int batchSize);
    }

    private static int drain(int maxBatches, int batchSize, Batch batch) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = batch.purge(batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
