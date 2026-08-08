package ug.co.smsone.shared.tenancy;

import java.util.UUID;

/**
 * One schema a tenant-axis sweep has to visit, and the axis that reaches it (ADR 0010 §3.4, Phase 5).
 *
 * <p><strong>Two fields that look redundant and are not.</strong> {@link #schema} is what the rows are
 * actually in — it names the home in a log line, keys a resumable cursor, and is the only stable
 * identity a fan-out has across runs. {@link #axis} is what gets pinned: {@code TenantContext} speaks
 * organizations, not schemas, so a sweep declares "this tenant" and {@code TenantSchemas.searchPathFor}
 * turns that into a {@code search_path}. Deriving one from the other at every call site would put the
 * mapping in eight jobs instead of in one place, and the two are only a bijection for silos —
 * {@link #pool()} has no organization at all.
 *
 * <p><strong>The pool's axis names no organization deliberately.</strong> A uuid that appears in no
 * {@code organization} row can never be placed anywhere, so it can only ever resolve to the shared
 * {@code tenant_pool} — which makes it the pooled schema's axis spelled in the only vocabulary
 * {@code TenantContext} has. That is the same {@code new UUID(0L, 0L)} eight jobs already spell out
 * for themselves; this record is where it stops being copied. It is <em>not</em>
 * {@code QueueSignals.PLATFORM_SCOPE}, which is the same literal naming a ROW SCOPE rather than an
 * axis — see that constant's note.
 */
public record TenantHome(String schema, UUID axis) {

    /**
     * The axis that reaches {@code tenant_pool}. Package-visible through {@link #pool()} only, so
     * nothing outside this file can mistake it for an organization it may query by.
     */
    private static final UUID POOLED_AXIS = new UUID(0L, 0L);

    public TenantHome {
        if (schema == null || axis == null) {
            throw new IllegalArgumentException("a tenant home needs both a schema and the axis that reaches it");
        }
    }

    /** The shared schema every unpromoted tenant lives in — always visited, always first. */
    public static TenantHome pool() {
        return new TenantHome(TenantSchemas.TENANT_POOL, POOLED_AXIS);
    }

    /**
     * A promoted tenant's own schema. Validated on the way in even though the caller read it out of
     * {@code platform.tenant_placement}: this name reaches a {@code SET search_path} that cannot bind
     * it as a parameter, and {@link TenantSchemas#requireSiloSchema} is the whole defence (ADR 0010 §3.1).
     */
    public static TenantHome silo(UUID orgId, String schema) {
        if (!TenantSchemas.siloSchema(orgId).equals(TenantSchemas.requireSiloSchema(schema))) {
            // The schema name is derived from the org id with zero database reads, so a registry row
            // whose two columns disagree is not a naming quirk — it is a row that would route this
            // tenant's sweep into some other tenant's schema.
            throw new IllegalArgumentException(
                    "placement row disagrees with itself: org " + orgId + " is recorded in schema '"
                            + schema + "', but its schema can only ever be '"
                            + TenantSchemas.siloSchema(orgId) + "' (ADR 0010 §3.1)");
        }
        return new TenantHome(schema, orgId);
    }

    /** True for {@code tenant_pool}: the home that holds every tenant nobody has promoted. */
    public boolean pooled() {
        return TenantSchemas.TENANT_POOL.equals(schema);
    }
}
