package ug.co.smsone.shared.tenancy;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Schema names, and the {@code search_path} each {@link Tenant} state resolves to (ADR 0010 §3.1).
 * The naming is pure policy — {@link #siloSchema} derives a schema from an organization id with no
 * reads at all, which is what §3.1 means by "zero database reads to route".
 *
 * <p><strong>Since Phase 2 this routes for real, and since Phase 5 it routes per tenant.</strong> A
 * platform axis reaches {@code platform}, no axis at all reaches the empty {@code no_tenant}, and a
 * tenant axis reaches whichever schema {@code platform.tenant_placement} says that organization lives
 * in — {@code tenant_pool} for every tenant nobody has promoted, {@code t_<32hex>} for one that has
 * been. The schema a statement lands in is decided entirely here.
 *
 * <p><strong>The one read, and where it lives.</strong> "Has this tenant been promoted?" is a row and
 * cannot be derived, so {@link #searchPathFor} asks {@link TenantRoutes}, which memoizes it. That split
 * is deliberate: naming stays a pure function anybody can call and reason about, and the single fact
 * that has to come from the database is behind one class with one cache and one documented staleness
 * window. Everything else in this file still reads and writes nothing.
 *
 * <p>{@link #siloSchema} and its guard carry the whole injection defence: a {@code search_path} cannot
 * be a bound parameter, so the name is interpolated into the statement and validation is all there is.
 * Every silo name that reaches a {@code SET} — derived here, or read back out of the registry by
 * {@code TenantRoutes} — goes through {@link #requireSiloSchema} first.
 */
public final class TenantSchemas {

    /**
     * Real schema, deliberately empty. An unqualified tenant table resolved here fails with
     * {@code relation "ticket" does not exist} — ugly on purpose, and unreachable in a request that
     * was refused at the edge for having no org (ADR 0010 §3.3 layers 1 and 3).
     */
    public static final String NO_TENANT = "no_tenant";

    /**
     * Holds {@code pg_trgm} and nothing else, so it can sit on every tenant's path without putting
     * one tenant's rows on another's: fallthrough reaches functions and operators only. That is what
     * makes a multi-element {@code search_path} safe here where it usually is not.
     */
    public static final String EXTENSIONS = "ext";

    /** The platform tier's own schema: the 28 tables one copy of the system shares (ADR 0010 §2). */
    public static final String PLATFORM = "platform";

    /**
     * The shared tenant schema. Every organization lives here until it is promoted to a silo, and
     * promotion is a placement flip rather than a code change — which is the property that makes
     * extraction rehearsable rather than a one-off (ADR 0010 §1).
     */
    public static final String TENANT_POOL = "tenant_pool";

    /**
     * {@code ext} is on both paths and holds pg_trgm and nothing else. That is what makes a
     * multi-element {@code search_path} safe here where it usually is not: fallthrough reaches
     * FUNCTIONS, never another tenant's rows. `public` is deliberately absent from both — it holds
     * nothing after Phase 2, and depending on it is what would stop a tenant being liftable to its own
     * database.
     */
    private static final String PLATFORM_SEARCH_PATH = PLATFORM + ", " + EXTENSIONS;

    private static final String POOLED_TENANT_SEARCH_PATH = TENANT_POOL + ", " + EXTENSIONS;

    /** ADR 0010 §3.1 verbatim. Lower-case hex only: {@code UUID.toString()} never produces anything else. */
    private static final Pattern SILO_SCHEMA = Pattern.compile("^t_[0-9a-f]{32}$");

    private TenantSchemas() {}

    /**
     * The schema a promoted tenant's rows live in, derived from {@code organization.id} with
     * <strong>zero database reads</strong> — routing must not need a query to decide where to route.
     * 34 characters, well under Postgres' NAMEDATALEN of 63.
     */
    public static String siloSchema(UUID orgId) {
        // Validated even though it was just derived: this method and requireSiloSchema are the only
        // two ways a silo name is ever minted, so proving it here means every caller downstream is
        // holding a name that cannot break out of the SET statement it gets interpolated into.
        return requireSiloSchema("t_" + orgId.toString().replace("-", ""));
    }

    /**
     * @throws IllegalArgumentException if the name is not a silo schema. Nothing else may reach a
     *     {@code SET search_path}.
     */
    public static String requireSiloSchema(String schemaName) {
        if (schemaName == null || !SILO_SCHEMA.matcher(schemaName).matches()) {
            throw new IllegalArgumentException(
                    "not a tenant silo schema name (expected t_<32 lower-case hex>): " + schemaName);
        }
        return schemaName;
    }

    /**
     * The {@code search_path} to set on a connection borrowed under {@code tenant}. A SQL fragment,
     * not a value — it is interpolated, which is why every name in it is either a constant here or
     * has been through {@link #requireSiloSchema}.
     *
     * <p>Called once per connection borrow, so the tenant branch is the one place in this system where
     * a registry read sits on the hot path. {@link TenantRoutes} is what makes that affordable, and its
     * class note is where the cost and the staleness window are written down.
     */
    public static String searchPathFor(Tenant tenant) {
        return switch (tenant) {
            // The org id is READ here, and this is the line promotion turns on. TenantRoutes resolves
            // platform.tenant_placement to a schema name: tenant_pool for an unpromoted tenant, its own
            // t_<32hex> for a promoted one. That is why promotion is a row change and not a deployment
            // — nothing in the binary names a silo, and every consumer of a tenant connection gets the
            // new home on its next borrow.
            case Tenant.Org org -> tenantSearchPath(TenantRoutes.homeOf(org.orgId()));
            case Tenant.Platform ignored -> PLATFORM_SEARCH_PATH;
            // Never "leave the connection as it is", and never "fall back to platform". Both would
            // serve a tenant-less request from whichever schema the last borrower happened to leave
            // on the connection, which is the failure mode this whole mechanism exists to remove.
            // No `ext` here either: work with no axis has no business resolving trigram operators.
            case Tenant.Absent ignored -> NO_TENANT;
        };
    }

    /**
     * A tenant home plus {@code ext}, which is the shape every tenant path has. The pooled constant is
     * kept as a literal rather than built here so that {@code TenantSchemasTest}'s assertion about the
     * exact string is still an assertion about a written-down name and not the method restated.
     *
     * <p>Public since ADR 0011 for exactly one caller: {@code TenantRoutingDataSource} resolves the
     * whole route pair once per borrow ({@code TenantRoutes.routeOf}) and needs the path for the schema
     * half without asking {@link #searchPathFor} to resolve the route a second time. Everything else
     * keeps going through {@link #searchPathFor}, which is the method that knows about axes.
     */
    public static String tenantSearchPath(String home) {
        return TENANT_POOL.equals(home) ? POOLED_TENANT_SEARCH_PATH
                : requireSiloSchema(home) + ", " + EXTENSIONS;
    }
}
