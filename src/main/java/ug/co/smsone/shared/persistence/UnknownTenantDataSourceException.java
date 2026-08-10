package ug.co.smsone.shared.persistence;

/**
 * A placement row named a datasource this deployment has no pool for (ADR 0011 §4.2).
 *
 * <p>Thrown from {@link TenantDataSources#poolFor} — which is to say from the connection-borrow path —
 * so it surfaces wherever a routed borrow does. It exists as its own type because the failure must
 * stay <strong>per tenant, never per pod</strong>: {@code TenantSchemaFloor} reads the same registry
 * row at the edge and turns the same fact into that one tenant's 503 + {@code Retry-After} before a
 * borrow ever happens, and {@code TenantFanOut} withholds that tenant's home from every sweep. This
 * exception is the backstop for the paths neither of those covers (a job that pins the tenant
 * directly, a stale route memo racing a config rollback) — loud, naming the row and the config key,
 * and never a misroute: the one thing this must not do is guess another pool.
 */
public final class UnknownTenantDataSourceException extends IllegalStateException {

    private final String datasourceName;

    UnknownTenantDataSourceException(String datasourceName) {
        super("no datasource named '" + datasourceName + "' is configured — a tenant_placement row"
                + " selects it, and this deployment has no pool to serve that tenant from."
                + " app.tenancy.datasources." + datasourceName + ".url is the config key that would"
                + " create one (ADR 0011 §4.2). Refusing the borrow rather than guessing a pool: a"
                + " guess is a misroute, which is ADR 0010 §1's worst failure.");
        this.datasourceName = datasourceName;
    }

    public String datasourceName() {
        return datasourceName;
    }
}
