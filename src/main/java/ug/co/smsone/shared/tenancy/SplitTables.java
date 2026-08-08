package ug.co.smsone.shared.tenancy;

import java.util.List;
import java.util.UUID;

/**
 * The seven tables that exist in BOTH schemas, and the one rule that says which copy a row belongs to
 * (ADR 0010 §2): <strong>non-null {@code org_id} → the tenant copy, null → the platform copy.</strong>
 *
 * <p>{@code audit_log}, {@code document}, {@code exchange_job}, {@code exchange_job_error},
 * {@code integration}, {@code integration_setting}, {@code maintenance_window}. Same DDL in both
 * directories under {@code db/migration}; same table, two homes.
 *
 * <p><strong>Null does not mean the same thing in all seven, and that is the point.</strong> The
 * per-table meaning is written at each table's own routing site, because getting it wrong there is what
 * misfiles a row:
 *
 * <ul>
 *   <li>{@code audit_log} — null is a PLATFORM-LEVEL event (impersonation lifecycle, platform key
 *       mint/revoke, a settings or feature-flag change). Org rows are that tenant's compliance record
 *       and travel with it; null-org rows stay, because a support investigation cannot fan out.</li>
 *   <li>{@code document} — null is a PERSONAL document, not an unknown tenant. It belongs to a human,
 *       and it must NOT travel when their organization is extracted.</li>
 *   <li>{@code exchange_job} — null is a platform-scoped handler (V24:11). Org jobs are the tenant's
 *       exchange history, with artifacts and retention obligations attached.</li>
 *   <li>{@code exchange_job_error} — has no {@code org_id} of its own; it follows its parent job.</li>
 *   <li>{@code integration} — null is the PLATFORM DEFAULT that any org without an override of its own
 *       resolves to (V33:15). Both copies are read on one resolution.</li>
 *   <li>{@code integration_setting} — has no {@code org_id}; it follows its parent integration.</li>
 *   <li>{@code maintenance_window} — null is a PLATFORM-WIDE window, read on every org request
 *       regardless of tenant; a non-null one is that tenant's own.</li>
 * </ul>
 *
 * <h2>How a split table is actually reached, and why there are two mechanisms</h2>
 *
 * <p><strong>The platform copy is named explicitly</strong> — {@code platform.audit_log},
 * {@code @Table(schema = "platform")} — exactly like every platform-tier table. That is what lets a
 * request running on a tenant's {@code search_path} read the platform-wide maintenance windows or fall
 * back to the platform-default integration without leaving its own axis.
 *
 * <p><strong>The tenant copy is reached UNQUALIFIED, on that org's axis</strong>, and the
 * {@code search_path} decides which schema that is. This is the only form that survives Phase 5:
 * a promoted tenant's rows live in {@code t_<32hex>}, and no mapping compiled into the binary can name
 * that. Which is why this class hands out schema NAMES only for the two cases that genuinely cannot
 * change axis — a write inside a caller's transaction ({@link #homeOf}) and a cross-home queue sweep
 * ({@link #homes}) — and why both are marked for Phase 5 rather than left to be discovered.
 */
public final class SplitTables {

    /**
     * The platform home. Its name is fixed and compiled in, which is the property the whole design
     * leans on: {@code platform} is never on a tenant's {@code search_path}, so it is only ever reached
     * by being named.
     */
    public static final String PLATFORM = "platform";

    /**
     * The pooled tenant home — where every tenant lives until it is promoted.
     *
     * <p><strong>PHASE 5 MUST REVISIT EVERY USE OF THIS CONSTANT.</strong> Naming a tenant home in a
     * string is correct exactly while there is one, and promotion ends that: a promoted org's rows are
     * in {@code t_<32hex>} and a write sent here would land in the pool, producing the one failure ADR
     * 0010 §1 calls the worst this design can produce — a row whose {@code org_id} disagrees with its
     * schema. Every caller reaches it through {@link #homeOf} or {@link #homes}, so the fix is confined
     * to those two methods plus {@code platform.tenant_placement} once Phase 4 has built it.
     */
    public static final String TENANT_POOL = "tenant_pool";

    private SplitTables() {}

    /**
     * The schema holding the split-table row for this {@code org_id} — {@link #PLATFORM} for null, the
     * org's tenant home otherwise.
     *
     * <p><strong>Use this only where the axis cannot be changed.</strong> Pinning
     * {@link TenantContext#runAs} around the work is the better answer wherever a transaction is not
     * already open, because it routes a promoted tenant correctly for free. The case this exists for is
     * {@code AuditLogImpl}: it writes inside the caller's transaction, on the caller's connection, and
     * the caller is frequently on a different axis than the row's org (a platform operator suspending
     * an org, a signup completing, the gateway posting an edge denial, a nightly job expiring a trial).
     * {@code TenantContext.set} throws inside an active transaction — deliberately, since the
     * connection is already bound and the switch would be a silent no-op — so naming the schema is the
     * only mechanism left.
     */
    public static String homeOf(UUID orgId) {
        return orgId == null ? PLATFORM : TENANT_POOL;
    }

    /**
     * Every home a cross-home sweep has to visit, platform first.
     *
     * <p>For the work that is not any one tenant's: the exchange queue's {@code SKIP LOCKED} claim used
     * to be a single cluster-wide scan and is now one scan per home. Platform first so a platform-scoped
     * job is never starved behind tenant traffic.
     *
     * <p>Bounded by the number of HOMES, never by the number of tenants — that is the property ADR 0010
     * §1 rejected uniform schema-per-tenant to keep. Phase 5 adds the silos (ceiling 200, §8 Q1) and
     * Phase 3 replaces the sweep entirely with {@code platform.queue_signal} and a two-step claim, at
     * which point a home with nothing to do costs nothing to skip.
     */
    public static List<String> homes() {
        return List.of(PLATFORM, TENANT_POOL);
    }
}
