package ug.co.smsone.shared.tenancy.promotion;

import java.time.Instant;
import java.util.UUID;

/**
 * The HTTP half of a promotion freeze: an org-scoped {@code maintenance_window} in RESTRICT mode, which
 * {@code MaintenanceFilter} ({@code @Order 4}) turns into 503 + {@code Retry-After} on every org write
 * for the length of the window (ADR 0010 §6 hop 0→1).
 *
 * <p><strong>A port in {@code shared}, implemented in {@code maintenance.internal}</strong> — AGENTS
 * §2.3's shape, and the same seam as {@code shared.security.OrgAuthorization}. {@code shared} may not
 * compile-depend on a business module, and the window's validation, audit row and soft-delete
 * semantics belong to the module that owns the table. One difference from the other ports, and it is
 * deliberate: absence here is <strong>not</strong> a no-op. A promoter that could not open the HTTP
 * freeze would copy rows while requests were still writing them, so the promoter refuses to start.
 *
 * <h2>The window travels with the tenant, and that is not an accident</h2>
 *
 * <p>{@code maintenance_window} is a split table whose org-scoped rows are TENANT-tier (ADR 0010 §2 row
 * 25), so the freeze row is written into whichever schema the tenant currently lives in — and is
 * therefore copied to the destination along with everything else. That is what keeps the gate
 * continuous across the placement flip: before the flip the filter reads the window out of the source
 * schema, after it out of the destination, and there is no instant in between where an org write is
 * allowed through. It also keeps the row counts on both sides equal, because the window is one more row
 * the copy is expected to move.
 *
 * <p>Both calls therefore have to run on the ORG's axis ({@code TenantContext.runAs}), which is the
 * promoter's job — {@link #close} runs after the flip, so "the org's axis" is by then the destination
 * schema, where the copied row is.
 */
public interface HttpWriteFreeze {

    /**
     * Opens a RESTRICT window for {@code orgId} ending at {@code until}, and returns its id.
     *
     * <p>Must be called on {@code orgId}'s tenant axis and outside a transaction: the row is
     * tenant-tier, and the implementation opens its own.
     */
    UUID open(UUID orgId, Instant until, String message);

    /**
     * Cancels the window {@link #open} returned. Must be called on {@code orgId}'s axis — which, after
     * a promotion has flipped the placement, is the destination schema holding the copied row.
     *
     * <p>Idempotent from the caller's point of view: a window that is already gone is not an error,
     * because the alternative is a promoter that fails at its last step over a row it no longer needs.
     */
    void close(UUID orgId, UUID windowId);
}
