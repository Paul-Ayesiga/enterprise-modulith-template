package ug.co.smsone.shared.tenancy;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Schema names, and the {@code search_path} each {@link Tenant} state resolves to (ADR 0010 §3.1).
 * Pure naming policy — no Spring, no database reads, so it can be called from anywhere including the
 * connection-borrow path.
 *
 * <p><strong>Since Phase 2 this routes for real.</strong> A platform axis reaches {@code platform}, a
 * tenant axis reaches {@code tenant_pool}, and no axis at all reaches the empty {@code no_tenant}. The
 * schema a statement lands in is now decided entirely here, which is why this method is the one place
 * Phase 5 edits to send a promoted tenant to its own silo.
 *
 * <p>{@link #siloSchema} and its guard are written now, ahead of the phase that needs them, because
 * the regex is the only thing standing between a schema name and SQL injection: a {@code search_path}
 * cannot be a bound parameter, so the name is interpolated into the statement and validation is the
 * whole defence. That is not a thing to invent under promotion-day pressure.
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
     */
    public static String searchPathFor(Tenant tenant) {
        return switch (tenant) {
            // The org id is deliberately unread in Phase 1. Phase 5 looks it up in
            // platform.tenant_placement and calls siloSchema(orgId) for a SILO row; a pooled tenant
            // keeps this path, which is why promotion is a placement flip and not a code change.
            // Phase 5 looks the org up in platform.tenant_placement and returns siloSchema(orgId) for
            // a promoted tenant; an unpromoted one keeps this path. That is why promotion is a row
            // change and not a deployment.
            case Tenant.Org ignored -> POOLED_TENANT_SEARCH_PATH;
            case Tenant.Platform ignored -> PLATFORM_SEARCH_PATH;
            // Never "leave the connection as it is", and never "fall back to platform". Both would
            // serve a tenant-less request from whichever schema the last borrower happened to leave
            // on the connection, which is the failure mode this whole mechanism exists to remove.
            // No `ext` here either: work with no axis has no business resolving trigram operators.
            case Tenant.Absent ignored -> NO_TENANT;
        };
    }
}
