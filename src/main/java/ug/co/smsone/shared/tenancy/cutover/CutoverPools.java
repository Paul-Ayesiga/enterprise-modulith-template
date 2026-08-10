package ug.co.smsone.shared.tenancy.cutover;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.persistence.TenantDataSources;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;

/**
 * Resolves a datasource NAME to the raw pool a cutover moves bytes through — the one place this
 * package touches {@code TenantDataSources}, so the seam between "the registry that serves requests"
 * (ADR 0011 §4) and "the machinery that moves a tenant" (§7) is one file wide.
 *
 * <p><strong>Raw pools, never the router.</strong> Same rule, same reason as {@code TenantRoutes} and
 * {@code TenantSchemaMigrator}: the routing DataSource sets {@code search_path} from
 * {@code TenantContext} on every borrow, and a cutover addresses two databases by explicit
 * schema-qualified statements from no particular axis — and mid-move the router's own answer for this
 * tenant is precisely the thing in flux.
 */
@Component
class CutoverPools {

    private final HikariDataSource primary;
    private final ObjectProvider<TenantDataSources> registry;

    /**
     * @param poolDataSource the {@code @FlywayDataSource} Hikari pool — the platform database. Taken
     *     by concrete type so the compiler proves this is not the routing wrapper.
     * @param registry provider-resolved because a deployment with no remote datasources configured
     *     has no registry worth failing a context over; a cutover ASKED for on such a deployment
     *     fails right here, naming the missing config, which is the per-tenant failure ADR 0011 §4.2
     *     requires — never a boot-time one.
     */
    CutoverPools(HikariDataSource poolDataSource, ObjectProvider<TenantDataSources> registry) {
        this.primary = poolDataSource;
        this.registry = registry;
    }

    DataSource poolFor(String datasourceName) {
        if (TenantPlacement.PRIMARY_DATASOURCE.equals(datasourceName)) {
            return primary;
        }
        TenantDataSources dataSources = registry.getIfAvailable();
        if (dataSources == null) {
            throw new TenantCutoverException("no tenant datasource registry is configured, so '"
                    + datasourceName + "' names no pool this deployment can reach. Configure"
                    + " app.tenancy.datasources." + datasourceName + ".url/.username/.password"
                    + " (ADR 0011 §4.1) and retry — nothing has been moved or frozen.");
        }
        return dataSources.poolFor(datasourceName);
    }
}
