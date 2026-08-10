package ug.co.smsone.shared.tenancy.placement;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.persistence.MigrationScripts;
import ug.co.smsone.shared.persistence.TenantMigrationRunner;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * <strong>Creates</strong> one tenant schema and brings it to head (ADR 0010 §4.2, §4.3). The DDL half
 * of provisioning, and the only place in this application that a tenant schema comes into existence.
 *
 * <h2>Why this is not {@code TenantMigrationRunner.fanOut}</h2>
 *
 * <p>The two are deliberately disjoint and the seam is a single flag. {@code TenantMigrationRunner}
 * configures every tenant pass with {@code createSchemas(false)} and says why in its own comment: a
 * migration runner that created schemas would turn a typo in a placement row into a new, empty,
 * unserved tenant. Schema CREATION is provisioning's, which is this class. Two further reasons keep
 * them apart rather than merged:
 *
 * <ul>
 *   <li><strong>Connection arithmetic.</strong> The fan-out needs {@code 2 x workers + 2} connections
 *       to make progress — Flyway holds two per instance — and it takes them all at once. This runs on
 *       a request thread against a pool sized for serving traffic, so it takes the two Flyway needs and
 *       nothing else.</li>
 *   <li><strong>Failure direction.</strong> The fan-out must never abort on one tenant: it records and
 *       continues, because the alternative is a fleet-wide stop over one schema. Provisioning is the
 *       opposite — one tenant is the entire scope, and the caller has to hear about it, because the
 *       signup that asked for this tenant must not proceed to announce it.</li>
 * </ul>
 *
 * <p>What they DO share is {@link MigrationScripts}, and sharing it is the point: it holds the whole
 * of the Flyway contract §4.2 measured (supply both providers and the {@code Scanner} is never built;
 * refuse a {@code .sql.conf} in the tenant sequence), and a second copy of that reasoning is a second
 * place for it to be got wrong.
 *
 * <p><strong>The raw pool, never the router.</strong> {@code HikariDataSource} resolves to
 * {@code poolDataSource} — the {@code @FlywayDataSource} bean — because {@code TenantRoutingDataSource}
 * sets {@code search_path} from {@code TenantContext} on every borrow and migration pins no tenant, so
 * the router would hand Flyway {@code no_tenant}; Flyway 12.4.0 then fails with "Unable to determine
 * current schema as search_path is empty", a message that names this exact mistake.
 *
 * <p><strong>Parallel-safe, which is what lets a fleet pass and a signup run at the same time.</strong>
 * Flyway's Postgres advisory lock derives its discriminator from
 * {@code quote(schema, "flyway_schema_history").hashCode()} — per SCHEMA, not per database — so this
 * serializes against another writer of the SAME schema and against nothing else.
 */
@Component
public class TenantSchemaMigrator {

    private final HikariDataSource pool;
    private final MigrationScripts scripts;

    TenantSchemaMigrator(HikariDataSource poolDataSource) {
        this.pool = poolDataSource;
        // The same location and the same refusal the fleet runner uses — named from its constant so the
        // two cannot drift into migrating different sequences into the same schemas. `true` forbids a
        // `.sql.conf` here: §4.2 bars executeInTransaction=false from the tenant sequence, because a
        // non-transactional failure writes `success = false` and wedges that schema until repair(),
        // where the transactional default rolls back whole and leaves it retryable.
        this.scripts = MigrationScripts.fromClasspath(TenantMigrationRunner.TENANT_LOCATION, true);
    }

    /**
     * Creates {@code schemaName} if it is absent and migrates it to head.
     *
     * <p>Idempotent, which is what makes a retry after a failure the same call: a schema already at
     * head applies nothing and still reports the version it is at.
     *
     * @param schemaName {@code tenant_pool} or a {@code t_<32hex>} silo — validated, because a schema
     *     name cannot be a bound parameter in the DDL that follows and validation is the whole defence
     * @return the version the schema now sits at, e.g. {@code "53"}
     */
    public String migrate(String schemaName) {
        return migrate(pool, schemaName);
    }

    /**
     * The same creation-and-migrate against a caller-supplied database — ADR 0011 §7.2 step 1: a
     * cutover's destination is built "by the same code that builds any silo", and this overload is
     * what makes that sentence literal rather than aspirational. Same scripts (one
     * {@link MigrationScripts} instance, so the two paths cannot drift), same refusals, same
     * idempotence; only which database the schema comes into existence in moves.
     *
     * @param dataSource a RAW pool for the destination — never the routing DataSource, for the class
     *     note's reason
     */
    public String migrate(javax.sql.DataSource dataSource, String schemaName) {
        String schema = requireTenantSchema(schemaName);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .resourceProvider(scripts)
                .javaMigrationClassProvider(scripts)
                .schemas(schema)
                .defaultSchema(schema)
                // The one line that distinguishes this from the fleet runner — see the class note.
                .createSchemas(true)
                // A tenant schema is never baselined into existence. Baselining stamps a version onto a
                // schema nobody migrated, and that number is what §4.4's floor check decides whether to
                // serve a tenant on.
                .baselineOnMigrate(false)
                // Nothing in this application may drop a tenant's schema. Reclaiming one is a runbook
                // step with a human in it, not a method call away from the signup path.
                .cleanDisabled(true)
                .load();

        MigrateResult result = flyway.migrate();
        if (result.targetSchemaVersion != null) {
            return result.targetSchemaVersion;
        }
        // Nothing was pending, so Flyway reports no target. Ask the schema where it actually is —
        // "already at head" and "just migrated to head" must answer the same, or an idempotent re-run
        // would blank the version the registry is holding.
        MigrationInfo current = flyway.info().current();
        MigrationVersion version = current == null ? null : current.getVersion();
        String at = version == null ? null : version.getVersion();
        if (at == null) {
            // Reachable only if the sequence resolved to nothing — an EMPTY schema about to be recorded
            // as a migrated one, and a tenant that fails on its first query with `relation "ticket" does
            // not exist`. Loud here beats quiet there.
            throw new IllegalStateException(
                    "schema '" + schema + "' is at no version after a migrate — the tenant sequence at "
                            + scripts.location() + " resolved nothing (ADR 0010 §4.2)");
        }
        return at;
    }

    /**
     * The two shapes a tenant schema may take. Not {@link TenantSchemas#requireSiloSchema} alone,
     * because {@code tenant_pool} is a tenant schema too — and not "any string", because this name
     * reaches DDL that cannot bind it as a parameter.
     */
    private static String requireTenantSchema(String schemaName) {
        if (TenantSchemas.TENANT_POOL.equals(schemaName)) {
            return schemaName;
        }
        return TenantSchemas.requireSiloSchema(schemaName);
    }
}
