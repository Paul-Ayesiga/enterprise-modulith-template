package ug.co.smsone.maintenance;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side port over the maintenance calendar for surfaces that gate writes OUTSIDE the
 * {@code /api/**} filter chain (the MCP dispatcher today). Answers the same question
 * {@code MaintenanceFilter} answers for REST — is a RESTRICT window in effect for this org — so the
 * two surfaces can never disagree about whether the platform is writable.
 */
public interface MaintenanceState {

    /**
     * @return the end of the RESTRICT window currently blocking this org's writes (global windows
     *         count), or empty when writes are open. The instant is the caller's Retry-After signal.
     */
    Optional<Instant> writeBlockedUntil(UUID orgId);
}
