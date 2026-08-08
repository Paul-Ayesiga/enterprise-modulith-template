package ug.co.smsone.shared.tenancy.placement;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code platform.tenant_placement}: where a tenant's rows live, on which datasource, and
 * whether that home is fit to serve (ADR 0010 §4.2).
 *
 * <p>Three coordinates, and each one is a hop of §6's extraction journey: {@link #schemaName} is what
 * Phase 5 changes when a tenant is promoted, {@link #dataSourceName} is what Phase 7 changes when it
 * gets its own database, and {@link #state} is what says the tenant may be served at all in between.
 * A tenant's whole position on that journey is this record.
 *
 * <p>A record and not an entity, deliberately. Every statement against this table is a single
 * conditional write whose <em>outcome</em> is the answer the caller needs — "did I just become the one
 * that announces this tenant?" — and a JPA load-modify-flush cannot express that without a
 * read-then-write race in the middle of it. {@link TenantPlacements} owns the SQL; this is what it
 * hands back.
 */
public record TenantPlacement(
        UUID orgId,
        String schemaName,
        String dataSourceName,
        PlacementState state,
        String schemaVersion,
        String lastError,
        Instant updatedAt) {

    /**
     * The only datasource there is until ADR 0010 Phase 7 hands one tenant a database of its own. The
     * column carries it rather than a null so that hop is a value change on a populated column, not a
     * backfill against a table the request path reads.
     */
    public static final String PRIMARY_DATASOURCE = "primary";

    /** True when this tenant may be served — see {@link PlacementState#ACTIVE}. */
    public boolean isActive() {
        return state == PlacementState.ACTIVE;
    }
}
