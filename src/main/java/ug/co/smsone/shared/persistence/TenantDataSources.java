package ug.co.smsone.shared.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every database this deployment can route a tenant to, by name (ADR 0011 §4). {@code "primary"} is
 * the platform pool this class is handed, never one it builds; every other name is a Hikari pool built
 * from {@link TenantDataSourceProperties} — which on every existing deployment is an empty map, making
 * this a wrapper around the one pool that already existed.
 *
 * <p><strong>Pools are built eagerly and connect lazily, and the split is the availability
 * decision.</strong> Building at construction means malformed config — a URL no driver claims, a
 * negative pool size — still fails the boot, which is where config failures belong. But Hikari's
 * initialization-failure check is disabled ({@code initializationFailTimeout = -1}), so a remote
 * database that is DOWN at boot builds its pool anyway and fails at first borrow instead: that is one
 * tenant's outage, bounded by {@code connection-timeout}, where a boot failure would be every tenant's
 * (ADR 0011 §3 — availability coupling is the failure that section exists to prevent). The
 * per-datasource health report at startup is {@code MappedSchemaValidator}'s, not this class's.
 *
 * <p><strong>{@link #poolFor} never guesses.</strong> An unknown name throws
 * {@link UnknownTenantDataSourceException} rather than falling back to primary — the fallback would
 * send a remote tenant's statements to a database whose schema of that name is the abandoned SOURCE of
 * its cutover, which is ADR 0010 §1's misroute arrived at through configuration. The pretty version of
 * this refusal (per-tenant 503 + {@code Retry-After}) lives in {@code TenantSchemaFloor}, which checks
 * the same fact at the edge before any borrow; this throw is the backstop for non-request paths.
 */
public final class TenantDataSources implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSources.class);

    private final HikariDataSource primary;
    private final Map<String, HikariDataSource> remotes;

    public TenantDataSources(HikariDataSource primaryPool, TenantDataSourceProperties properties) {
        this.primary = primaryPool;
        Map<String, HikariDataSource> pools = new LinkedHashMap<>();
        properties.datasources().forEach((name, definition) -> pools.put(name, build(name, definition)));
        // Not Map.copyOf, which forgets insertion order — remoteNames() promises configuration order,
        // and the migration runner's per-datasource passes inherit it.
        this.remotes = java.util.Collections.unmodifiableMap(pools);
        if (!remotes.isEmpty()) {
            log.info("tenant datasources configured: {} (plus '{}', the platform pool). Placement rows"
                    + " select among them; a name in neither place fails per tenant (ADR 0011 §4.2).",
                    remotes.keySet(), TenantDataSourceProperties.PRIMARY);
        }
    }

    /**
     * The pool serving {@code datasourceName}. {@code "primary"} — and null, which is how a
     * pre-registry row spells the column default — is the platform pool.
     *
     * @throws UnknownTenantDataSourceException for a name no configuration backs; the caller must fail
     *     that one tenant, never the pod and never a different pool
     */
    public DataSource poolFor(String datasourceName) {
        if (datasourceName == null || TenantDataSourceProperties.PRIMARY.equals(datasourceName)) {
            return primary;
        }
        HikariDataSource pool = remotes.get(datasourceName);
        if (pool == null) {
            throw new UnknownTenantDataSourceException(datasourceName);
        }
        return pool;
    }

    /** True when {@link #poolFor} would answer rather than throw. What the edge and the fan-out ask. */
    public boolean isConfigured(String datasourceName) {
        return datasourceName == null
                || TenantDataSourceProperties.PRIMARY.equals(datasourceName)
                || remotes.containsKey(datasourceName);
    }

    /** The platform pool, for the callers that must never route: registry reads, Flyway, this class's owner. */
    public HikariDataSource primary() {
        return primary;
    }

    /** Every configured remote name, in configuration order. Empty on every existing deployment. */
    public Set<String> remoteNames() {
        return remotes.keySet();
    }

    /** The remote pools by name — what the migration runner's per-datasource fan-out is wired from. */
    public Map<String, DataSource> remotePools() {
        return Map.copyOf(remotes);
    }

    /**
     * Closes the pools this class BUILT and only those: the primary pool has its own bean lifecycle,
     * and closing it here would tear it down twice.
     */
    @Override
    public void close() {
        remotes.values().forEach(HikariDataSource::close);
    }

    private static HikariDataSource build(String name, TenantDataSourceProperties.Definition definition) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("tenant-ds-" + name);
        config.setJdbcUrl(definition.url());
        config.setUsername(definition.username());
        config.setPassword(definition.password());
        config.setMaximumPoolSize(definition.pool().maximumPoolSize());
        config.setMinimumIdle(definition.pool().minimumIdle());
        config.setConnectionTimeout(definition.pool().connectionTimeout().toMillis());
        // The line the class javadoc is about: -1 skips the initial connection attempt entirely, so an
        // unreachable remote is that datasource's tenants' outage and never this deployment's boot
        // failure. Config errors (bad URL syntax, no driver) still throw here, eagerly, which is the
        // half of "fail fast" that is actually about configuration.
        config.setInitializationFailTimeout(-1);
        // No config.setSchema: the routing DataSource pins search_path on every borrow itself, and a
        // pool-level default would be a second, quieter answer to the same question (the same reason
        // TenantMigrationJob's pool leaves it unset).
        return new HikariDataSource(config);
    }
}
