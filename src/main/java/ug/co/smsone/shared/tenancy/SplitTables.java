package ug.co.smsone.shared.tenancy;

import java.util.ArrayList;
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
 * ({@link #homes}) — and why, since Phase 5, both of them read {@code platform.tenant_placement}
 * through {@link TenantRoutes} rather than assuming the pool.
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
     * <p><strong>It is a home, not THE tenant home, and the difference is Phase 5.</strong> Naming a
     * tenant home in a string is correct exactly while there is one, and promotion ended that: a
     * promoted org's rows are in {@code t_<32hex>} and a write sent here would land in the pool,
     * producing the one failure ADR 0010 §1 calls the worst this design can produce — a row whose
     * {@code org_id} disagrees with its schema. So this constant is now only ever the answer for a
     * tenant the registry places here; ask {@link #homeOf} rather than reaching for it directly.
     */
    public static final String TENANT_POOL = "tenant_pool";

    private SplitTables() {}

    /**
     * The schema holding the split-table row for this {@code org_id} — {@link #PLATFORM} for null, and
     * otherwise whichever tenant home {@code platform.tenant_placement} places that organization in.
     *
     * <p><strong>Since Phase 5 this reads the registry</strong> (through {@link TenantRoutes}, memoized
     * — see its class note for the cost and the staleness window). It has to: {@code AuditLogImpl}
     * routes every audit row through here, and an audit row about a promoted tenant addressed to
     * {@code tenant_pool} is exactly the misfiled row above — one that {@code pg_dump -n t_<hex>} would
     * then silently omit from that tenant's compliance record.
     *
     * <p><strong>Use this only where the axis cannot be changed.</strong> Pinning
     * {@link TenantContext#runAs} around the work is still the better answer wherever a transaction is
     * not already open, because it routes through the connection rather than through a string. The case
     * this exists for is {@code AuditLogImpl}: it writes inside the caller's transaction, on the
     * caller's connection, and the caller is frequently on a different axis than the row's org (a
     * platform operator suspending an org, a signup completing, the gateway posting an edge denial, a
     * nightly job expiring a trial). {@code TenantContext.set} throws inside an active transaction —
     * deliberately, since the connection is already bound and the switch would be a silent no-op — so
     * naming the schema is the only mechanism left.
     *
     * <p>The name it returns is safe to interpolate: it is one of two compiled-in constants or a silo
     * name {@code TenantSchemas.requireSiloSchema} has validated.
     *
     * <p><strong>SINCE ADR 0011 A SCHEMA NAME IS HALF AN ADDRESS, AND THIS RETURNS THE OTHER HALF OF
     * NOTHING.</strong> Every caller interpolates the answer into a statement issued on the connection
     * it is already holding, which is correct exactly while every schema is in one database. It no
     * longer is. Both directions now fail, and neither one is quiet in a useful way:
     *
     * <ul>
     *   <li>a platform-axis caller — an operator suspending an organization, a nightly job lapsing a
     *       trial — writing a row for a REMOTE org names {@code t_<hex>} on a primary connection, where
     *       that schema does not exist;</li>
     *   <li>a remote tenant's own request recording a platform-level event names
     *       {@code platform.audit_log} on the remote, whose {@code platform} schema deliberately holds
     *       only {@code event_publication} (ADR 0011 §5.1).</li>
     * </ul>
     *
     * <p>So this method still answers WHICH SCHEMA and has stopped being sufficient on its own. Wrap the
     * statement in {@link CrossDatabaseWrites#runInHomeOf}, which makes the connection and the name
     * agree by construction — it is a no-op (same connection, same transaction) whenever the row's home
     * and the caller's axis are in the same database, which is every call on every deployment with no
     * remote datasource configured.
     */
    public static String homeOf(UUID orgId) {
        return orgId == null ? PLATFORM : TenantRoutes.homeOf(orgId);
    }

    /**
     * Every home a cross-home sweep has to visit, platform first — the pool, plus every silo the
     * registry names.
     *
     * <p>For the work that is not any one tenant's: the exchange queue's {@code SKIP LOCKED} claim used
     * to be a single cluster-wide scan and is now one scan per home. Platform first so a platform-scoped
     * job is never starved behind tenant traffic.
     *
     * <p>Bounded by the number of HOMES, never by the number of tenants — that is the property ADR 0010
     * §1 rejected uniform schema-per-tenant to keep.
     *
     * <p><strong>This is "where could a row be", not "where may work happen".</strong> It deliberately
     * includes a home a promotion is holding, because a row in it is still a row. Anything that WRITES
     * per home — every fanned-out job — must take its list from {@link TenantFanOut} instead, which
     * subtracts the frozen and the half-built ones. Handing this list to a sweep would let it write into
     * a schema a promoter is copying, which is the one thing the freeze exists to prevent.
     */
    public static List<String> homes() {
        List<String> homes = new ArrayList<>();
        homes.add(PLATFORM);
        homes.addAll(TenantRoutes.tenantHomes());
        return List.copyOf(homes);
    }
}
