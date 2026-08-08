package ug.co.smsone.shared.tenancy;

import java.util.UUID;

/**
 * Which tenancy axis the work on the current thread belongs to. Three states, and the third one is
 * the whole design (ADR 0010 §3.2).
 *
 * <p>{@link #ABSENT} is <em>not</em> "no opinion, use the default". It is the poison state:
 * {@code TenantRoutingDataSource} resolves it to the deliberately empty {@code no_tenant} schema, so
 * database work that reached this far without ever declaring an axis fails on its first unqualified
 * table instead of quietly reading through whatever {@code search_path} the pooled connection was
 * last left on. A fail-open default here is the one bug this design exists to make impossible.
 *
 * <p>Sealed, and every {@code switch} over it is exhaustive with no {@code default} branch. When a
 * fourth state arrives (Phase 7's per-database routing is the candidate), the compiler must stop at
 * every place that routes rather than let it fall into someone's catch-all.
 */
public sealed interface Tenant {

    /**
     * The platform axis: the routing registry, the catalogs, the person graph — the rows that belong
     * to no tenant and never travel with one.
     */
    Tenant PLATFORM = new Platform();

    /** Nobody declared an axis on this thread. Read the class note: this is a failure, not a default. */
    Tenant ABSENT = new Absent();

    /**
     * One tenant, identified by {@code organization.id} and never by its alias.
     * {@code OrganizationService.rename} exists and {@code uq_organization_alias_live} is partial, so
     * an alias is both mutable and reusable — a schema name derived from one would eventually point a
     * live connection at some other tenant's rows.
     */
    record Org(UUID orgId) implements Tenant {

        public Org {
            if (orgId == null) {
                throw new IllegalArgumentException(
                        "Tenant.Org needs an organization id — use Tenant.PLATFORM for platform-axis work");
            }
        }
    }

    /** One tenant's axis. Reads better than {@code new Tenant.Org(orgId)} at a pinning call site. */
    static Tenant of(UUID orgId) {
        return new Org(orgId);
    }

    /** @see #PLATFORM — use the constant; this exists so the {@code switch} can name the state. */
    record Platform() implements Tenant {}

    /** @see #ABSENT — use the constant; this exists so the {@code switch} can name the state. */
    record Absent() implements Tenant {}
}
