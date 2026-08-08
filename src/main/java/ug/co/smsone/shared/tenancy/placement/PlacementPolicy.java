package ug.co.smsone.shared.tenancy.placement;

import java.util.UUID;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * Where a NEW tenant is born (ADR 0010 §1, §4.3). One flag, two answers, and the difference between
 * them is the whole of what ADR 0010 decided — so it is a switch the owner can flip and measure
 * rather than a number to take on faith.
 *
 * <p><strong>{@link #POOLED} is the default and the ADR's decision.</strong> The measurements behind
 * it are in §1 and they are not close: one tenant schema is 239 relations, so 5,000 of them is ~1.2M
 * {@code pg_class} rows, 5–6 GB of catalog and <strong>~16 GB of empty relation files</strong>; pgjdbc's
 * own {@code getColumns} against 300 real schemas extrapolates to <strong>~9 s of unfiltered JDBC
 * metadata on every JVM start</strong>; and cross-tenant fan-out hits a hard wall at
 * <strong>~3,200 UNION branches</strong> against 5,000 tenants, because lock slots run out.
 *
 * <p><strong>{@link #SILO_PER_ORG} exists so those numbers can be re-measured, not so they can be
 * doubted.</strong> Flip it in an environment, create tenants, and watch the catalog, the boot time
 * and the signup latency for yourself — ~200–330 ms of Flyway per schema, measured flat to 300
 * schemas (§4.2). What it must never be is a thing somebody switches on in production because the
 * word "isolation" is in it: schema separation inside one cluster buys almost nothing in security
 * terms (§1 — the same role reaches every schema), and the isolation guarantees this system actually
 * has live in {@code ApiPermissionEvaluator} and {@code OrgAuthorization}.
 */
public enum PlacementPolicy {

    /**
     * Every new tenant lands in the shared {@code tenant_pool}, which already exists.
     *
     * <p>ADR 0010 §4.3 calls this the strongest practical argument for the hybrid, and the reason is
     * an ordering hazard that simply does not arise here: signup creates no DDL, so nothing has to
     * happen before {@code OrganizationRegistered} is published and the registration transaction is
     * exactly what it was.
     */
    POOLED,

    /**
     * Every new tenant gets its own {@code t_<32hex>} schema, created and migrated before the tenant
     * is announced.
     *
     * <p><strong>The ordering is the point.</strong> Three {@code @ApplicationModuleListener}s hang
     * off {@code OrganizationRegistered} — the trial, the billing account and the search document —
     * and all three fire AFTER COMMIT, asynchronously, against tenant-tier tables. Create the schema
     * after the commit and all three race it; every new tenant silently gets no trial and no billing
     * account, and because they are outbox-retried the failures look like transient noise rather than
     * a broken signup (§4.3). {@link TenantProvisioner} is where that ordering is enforced.
     */
    SILO_PER_ORG;

    /**
     * The schema a tenant with this id is born into. No database read — a policy decision must be
     * answerable before the row that would answer it exists.
     */
    public String homeFor(UUID orgId) {
        return this == POOLED ? TenantSchemas.TENANT_POOL : TenantSchemas.siloSchema(orgId);
    }

    /**
     * Whether creating a tenant has to build something before the tenant may be announced — the
     * question ADR 0010 §4.3 turns on, asked in the words of the rule rather than in the words of the
     * flag. False for {@link #POOLED} (the home already exists), true for {@link #SILO_PER_ORG}.
     */
    public boolean buildsASchema() {
        return this != POOLED;
    }
}
