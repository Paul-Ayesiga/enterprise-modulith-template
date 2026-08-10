package ug.co.smsone.shared.tenancy;

import java.util.UUID;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;

/**
 * One schema a tenant-axis sweep has to visit, the axis that reaches it, and — since ADR 0011 — the
 * database it is in (ADR 0010 §3.4, Phase 5; ADR 0011 §4.3).
 *
 * <p><strong>Three fields that look redundant and are not.</strong> {@link #schema} is what the rows
 * are actually in — it names the home in a log line, keys a resumable cursor, and is the only stable
 * identity a fan-out has across runs. {@link #axis} is what gets pinned: {@code TenantContext} speaks
 * organizations, not schemas, so a sweep declares "this tenant" and the routing DataSource turns that
 * into a pool and a {@code search_path}. {@link #datasource} is which database the visit's borrows will
 * land on — the fan-out groups homes by it and log lines carry it, but <em>nothing builds a connection
 * from it</em>: the pin is the mechanism, the router resolves the same placement row, and a second path
 * to a pool would be a second chance to disagree with it. Deriving any field from another at every call
 * site would put the mapping in eight jobs instead of in one place.
 *
 * <p><strong>A remote home's work is two databases, and never one transaction.</strong> The rows live
 * on {@link #datasource}; the coordination state a sweep consults — {@code platform.queue_signal},
 * {@code platform.tenant_freeze}, {@code platform.shedlock} — lives on primary and nowhere else
 * (ADR 0011 §5). Reading a signal from inside a visit therefore means {@code callAsPlatform} around
 * its own statement, on its own borrow — and a {@code @Transactional} that thinks it spans the two is
 * <strong>silently two transactions</strong>: two connections, two commits, no shared fate. Proven
 * against two real containers (a remote rollback left the primary claim standing) before this shipped.
 * {@code TenantContext.set}'s throw-inside-a-transaction is the guard that makes the mistake loud.
 *
 * <p><strong>The pool's axis names no organization deliberately.</strong> A uuid that appears in no
 * {@code organization} row can never be placed anywhere, so it can only ever resolve to the shared
 * {@code tenant_pool} — which makes it the pooled schema's axis spelled in the only vocabulary
 * {@code TenantContext} has. That is the same {@code new UUID(0L, 0L)} eight jobs already spell out
 * for themselves; this record is where it stops being copied. It is <em>not</em>
 * {@code QueueSignals.PLATFORM_SCOPE}, which is the same literal naming a ROW SCOPE rather than an
 * axis — see that constant's note.
 */
public record TenantHome(String schema, UUID axis, String datasource) {

    /**
     * The axis that reaches {@code tenant_pool}. Package-visible through {@link #pool()} only, so
     * nothing outside this file can mistake it for an organization it may query by.
     */
    private static final UUID POOLED_AXIS = new UUID(0L, 0L);

    public TenantHome {
        if (schema == null || axis == null || datasource == null) {
            throw new IllegalArgumentException(
                    "a tenant home needs a schema, the axis that reaches it, and the datasource it is on");
        }
    }

    /**
     * The shared schema every unpromoted tenant lives in — always visited, always first, and always on
     * the platform database: {@code tenant_pool} exists nowhere else by construction (a remote database
     * holds silos and the minimal platform schema, ADR 0011 §5.1).
     */
    public static TenantHome pool() {
        return new TenantHome(TenantSchemas.TENANT_POOL, POOLED_AXIS, TenantPlacement.PRIMARY_DATASOURCE);
    }

    /**
     * A promoted tenant's own schema, on the datasource its placement row names. Validated on the way
     * in even though the caller read it out of {@code platform.tenant_placement}: this name reaches a
     * {@code SET search_path} that cannot bind it as a parameter, and
     * {@link TenantSchemas#requireSiloSchema} is the whole defence (ADR 0010 §3.1). The datasource NAME
     * carries no such validation — it is a map key, never SQL, and whether a pool of that name exists
     * is {@code TenantFanOut}'s question, answered before this record is ever built for a sweep.
     */
    public static TenantHome silo(UUID orgId, String schema, String datasource) {
        if (!TenantSchemas.siloSchema(orgId).equals(TenantSchemas.requireSiloSchema(schema))) {
            // The schema name is derived from the org id with zero database reads, so a registry row
            // whose two columns disagree is not a naming quirk — it is a row that would route this
            // tenant's sweep into some other tenant's schema.
            throw new IllegalArgumentException(
                    "placement row disagrees with itself: org " + orgId + " is recorded in schema '"
                            + schema + "', but its schema can only ever be '"
                            + TenantSchemas.siloSchema(orgId) + "' (ADR 0010 §3.1)");
        }
        return new TenantHome(schema, orgId, datasource == null
                ? TenantPlacement.PRIMARY_DATASOURCE : datasource);
    }

    /** A silo on the platform database — the pre-ADR-0011 shape, kept for the callers that mean it. */
    public static TenantHome silo(UUID orgId, String schema) {
        return silo(orgId, schema, TenantPlacement.PRIMARY_DATASOURCE);
    }

    /** True for {@code tenant_pool}: the home that holds every tenant nobody has promoted. */
    public boolean pooled() {
        return TenantSchemas.TENANT_POOL.equals(schema);
    }

    /** True when this home's borrows land on the platform database rather than a remote one. */
    public boolean onPrimary() {
        return TenantPlacement.PRIMARY_DATASOURCE.equals(datasource);
    }
}
