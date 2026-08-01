package ug.co.smsone.shared.persistence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention policy for {@link SoftDeletableEntity} rows. Soft delete hides a row; this window is what
 * eventually makes it <em>gone</em> — the difference between "deleted" as a UI state and deleted as an
 * erasure guarantee. A restore is only possible while the row is still inside the window.
 *
 * <p>{@code purgeEnabled} exists because the purge is irreversible: an operator investigating a bad
 * bulk delete needs a way to freeze the window without redeploying.
 *
 * <p>Sizing: {@code batchSize} bounds one transaction (and one lock footprint); {@code maxBatches}
 * bounds one run, so a huge backlog drains over several nights instead of overrunning the ShedLock
 * lease and blocking the table.
 */
@ConfigurationProperties(prefix = "app.persistence.soft-delete")
public record SoftDeleteProperties(
        @DefaultValue("true") boolean purgeEnabled,
        @DefaultValue("P30D") Duration retention,
        @DefaultValue("500") int batchSize,
        @DefaultValue("100") int maxBatches) {

    public SoftDeleteProperties {
        // A negative window would purge rows deleted in the future, i.e. everything — fail at startup
        // rather than at 4am. Zero is allowed: it is how tests and one-off erasures ask for "now".
        if (retention.isNegative()) {
            throw new IllegalArgumentException(
                    "app.persistence.soft-delete.retention must not be negative, but was " + retention);
        }
        if (batchSize < 1 || maxBatches < 1) {
            throw new IllegalArgumentException(
                    "app.persistence.soft-delete batch-size and max-batches must be at least 1");
        }
    }
}
