package ug.co.smsone.shared.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * Applies the tenant migration sequence to every tenant schema — {@code tenant_pool} and every silo —
 * as a separate, resumable, parallel pass that runs to completion <strong>before</strong> the
 * application rollout (ADR 0010 §4.2, §7 Phase 4).
 *
 * <h2>Why this is not a boot-time Flyway</h2>
 *
 * <p>{@code deploy/helm/smsone/templates/modulith.yaml} carries <strong>no {@code startupProbe}</strong>
 * and a {@code livenessProbe} of {@code initialDelaySeconds: 60, periodSeconds: 15, failureThreshold: 3}
 * — a ~105 s budget before the kubelet kills the pod — while Flyway runs before the servlet container
 * serves anything. A fan-out at startup is therefore a rollout that works until the fleet grows and then
 * fails in the least debuggable way available: every replica crash-looping with no log past "Migrating
 * schema". Worse, it would put the code and the schema on the same clock, and ADR 0010 §4.4 needs them
 * on different ones — a tenant migration ships in release N and the code that depends on it no earlier
 * than N+1, which only holds if the schema can move without the binary.
 *
 * <h2>What it does, in order</h2>
 *
 * <ol>
 *   <li>{@link #migratePlatform(Mode)} — the platform sequence, serially, first. It is the same set the
 *       application runs at boot, so on a live database it is an idempotent no-op costing one Flyway
 *       round; on a <em>fresh</em> one it is what creates {@code platform}, {@code tenant_pool},
 *       {@code ext}, {@code no_tenant} and {@code platform.tenant_placement}, none of which exist before
 *       the first app pod has ever started. Running it here also puts the schema ahead of the binary on
 *       an upgrade, which is the direction §4.4 requires and AGENTS §4.6 already guarantees is safe.</li>
 *   <li>{@link #discoverFleet()} — {@code tenant_pool}, plus every {@code t_<32 hex>} schema in the
 *       catalogue, plus every schema {@code platform.tenant_placement} names.</li>
 *   <li>{@link #fanOut(Mode, List)} — 8–16 workers, one Flyway per schema.</li>
 * </ol>
 *
 * <h2>Parallel is safe, and that is a fact about Flyway rather than a hope</h2>
 *
 * <p>{@code PostgreSQLConnection.lock} derives its advisory-lock discriminator from
 * {@code table.toString().hashCode()}, and that table is the <em>qualified</em>
 * {@code "<schema>"."flyway_schema_history"}. The lock is therefore per schema: two workers migrating
 * two tenants take two different advisory locks and never serialize, while two <em>runners</em> racing
 * on the same tenant still do. That is the exact property this design needs and the reason the fan-out
 * is not a loop.
 *
 * <h2>Never abort</h2>
 *
 * <p>One tenant's failure is recorded, marked in the registry, and the run continues. This is not new
 * doctrine — {@code SoftDeletePurgeJob}'s javadoc already says it: <em>loud AND complete, not loud
 * instead of complete</em>. A fan-out that stopped at the first failure would leave the rest of the
 * fleet at an unknown version, which is precisely the state {@code platform.tenant_placement} exists to
 * make impossible. The process exits non-zero with a manifest instead.
 *
 * <h2>No Spring, deliberately</h2>
 *
 * <p>Nothing here is a bean and nothing is component-scanned. It is a plain object over a
 * {@link DataSource}, which is what lets {@link TenantMigrationJob} run it from a {@code main} in a
 * Kubernetes Job with none of the application's context — no Keycloak issuer to reach, no Valkey, no
 * object store, no schedulers — and what lets the test suite call it directly against a container. It
 * lives beside {@code TenantRoutingDataSource} rather than in a new top-level {@code tools} package for
 * one concrete reason: a new direct sub-package of {@code ug.co.smsone} is a new Spring Modulith
 * application module, which would change {@code docs/modulith/} and {@code ApplicationModules.verify()}'s
 * view of the system for a class that is not part of the running system at all.
 */
public final class TenantMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationRunner.class);

    /** Mirrors {@code spring.flyway.locations} — asserted equal by {@code MigrationScriptsTest}. */
    public static final String PLATFORM_LOCATION = "db/migration/platform";

    /** The sequence this runner exists for. It runs nowhere else — not at boot, not from a test fixture. */
    public static final String TENANT_LOCATION = "db/migration/tenant";

    /** Mirrors {@code spring.flyway.schemas}; {@code public} is deliberately absent (ADR 0010 §4.1). */
    public static final List<String> PLATFORM_MANAGED_SCHEMAS = List.of(
            TenantSchemas.PLATFORM, TenantSchemas.TENANT_POOL, TenantSchemas.EXTENSIONS, TenantSchemas.NO_TENANT);

    /** Mirrors {@code spring.flyway.default-schema}. */
    public static final String PLATFORM_HISTORY_SCHEMA = TenantSchemas.PLATFORM;

    /** ADR 0010 §4.2's range is 8–16; 8 is the floor of it and the default the Kubernetes Job ships with. */
    public static final int DEFAULT_WORKERS = 8;

    private static final int MAX_WORKERS = 16;

    /**
     * Flyway <em>prepends</em> the target schema to whatever {@code search_path} the connection already
     * carries ({@code PostgreSQLConnection.changeCurrentSchemaTo}), so the connection's baseline decides
     * what a migration falls through to. Pinning it to {@code ext} is what makes every migration run
     * under exactly the path production reads under — {@code platform, ext} for the platform sequence and
     * {@code tenant_pool, ext} or {@code t_<hex>, ext} for a tenant one — with no {@code public} and,
     * critically, no other tenant's schema behind it.
     */
    private static final String BASELINE_SEARCH_PATH = TenantSchemas.EXTENSIONS;

    /** A registry row is a status line, not a log sink. Flyway stack traces do not belong in it whole. */
    private static final int MAX_RECORDED_ERROR = 2000;

    /**
     * Every pooled organization has a placement row naming {@code tenant_pool}, so one statement per
     * schema covers the whole pool. Deliberately an UPDATE and never an UPSERT: rows are inserted by
     * provisioning (ADR 0010 §4.3), and a migration runner that invented placement rows would be
     * asserting where a tenant lives — the registry's judgement, not this pass's. Zero rows updated is a
     * normal outcome, not an error: it means no organization is placed there yet.
     *
     * <p><strong>The {@code case} is the guard, and it is the whole reason this is not a plain
     * assignment.</strong> This runner does not own the {@code state} vocabulary; it only needs
     * {@code FAILED} to be reversible. Setting {@code ACTIVE} unconditionally would overwrite whatever
     * the registry means by a healthy state — the promotion lifecycle lives in this column too — so
     * {@code ACTIVE} is written only over a {@code FAILED} this runner itself put there.
     */
    private static final String CLEAR_PLACEMENT =
            "update platform.tenant_placement"
                    + " set schema_version = ?,"
                    + "     last_error = null,"
                    + "     state = case when state = 'FAILED' then 'ACTIVE' else state end,"
                    + "     updated_at = now()"
                    + " where schema_name = ?";

    /**
     * {@code schema_version} is written on the failure path too, and that is the point of it. A
     * transactional migration that fails rolls back whole, so the schema really is at V(n−1) and the
     * compiled-in version floor (§4.4) has to be able to read that as a fact rather than infer it from
     * the absence of a value.
     */
    private static final String FAIL_PLACEMENT =
            "update platform.tenant_placement"
                    + " set schema_version = ?,"
                    + "     last_error = ?,"
                    + "     state = 'FAILED',"
                    + "     updated_at = now()"
                    + " where schema_name = ?";

    /** Silos are named from {@code organization.id}, so the catalogue can be filtered without the registry. */
    private static final String TENANT_SCHEMAS_IN_CATALOGUE =
            "select schema_name from information_schema.schemata"
                    + " where schema_name = 'tenant_pool' or schema_name ~ '^t_[0-9a-f]{32}$'";

    private static final String SCHEMAS_IN_CATALOGUE = "select schema_name from information_schema.schemata";

    private static final String SCHEMAS_IN_REGISTRY = "select distinct schema_name from platform.tenant_placement";

    private final DataSource dataSource;
    private final MigrationScripts platformScripts;
    private final MigrationScripts tenantScripts;
    private final int workers;

    /**
     * @param dataSource wrapped so every borrow starts from {@link #BASELINE_SEARCH_PATH}; it must be
     *     able to supply {@link #connectionsRequired(int)} connections or the fan-out deadlocks on itself
     */
    public TenantMigrationRunner(
            DataSource dataSource, MigrationScripts platformScripts, MigrationScripts tenantScripts, int workers) {
        if (workers < 1 || workers > MAX_WORKERS) {
            throw new IllegalArgumentException(
                    "workers must be between 1 and " + MAX_WORKERS + " (ADR 0010 §4.2 measured 8–16), was " + workers);
        }
        this.dataSource = new FixedSearchPathDataSource(dataSource, BASELINE_SEARCH_PATH);
        this.platformScripts = platformScripts;
        this.tenantScripts = tenantScripts;
        this.workers = workers;
    }

    /** The production wiring: both sequences off the classpath, the tenant one refusing script config. */
    public static TenantMigrationRunner fromClasspath(DataSource dataSource, int workers) {
        return new TenantMigrationRunner(
                dataSource,
                MigrationScripts.fromClasspath(PLATFORM_LOCATION),
                MigrationScripts.fromClasspath(TENANT_LOCATION, true),
                workers);
    }

    /**
     * Connections the pool must be able to hand out for {@code workers} to make progress. Flyway holds
     * <strong>two</strong> per instance — a main connection for the history table and a migration
     * connection for the script — so a pool sized to the worker count alone deadlocks with every worker
     * holding one and waiting for its second. The spare pair is for the registry writes, which run on the
     * worker thread after its Flyway is done.
     */
    public static int connectionsRequired(int workers) {
        return 2 * workers + 2;
    }

    /** What the runner was asked to do. Applies to every schema in the pass; there is no per-schema mode. */
    public enum Mode {

        /** Apply what is pending. The only mode the Kubernetes Job runs unattended. */
        MIGRATE,

        /**
         * {@code repair()} then {@code migrate()}, per schema. Repair alone is never the goal state — it
         * removes the {@code success = false} row a wedged schema is stuck behind and leaves that schema
         * still one migration short — so this mode finishes the job. It exists because a non-transactional
         * migration can still reach production through the platform sequence (AGENTS §4.6 permits it
         * there), and because a checksum drift on an applied script has the same shape and the same fix.
         */
        REPAIR,

        /** Read-only. Reports each schema's version and pending count and writes nothing, registry included. */
        INFO
    }

    /** One schema's outcome. {@code from}/{@code to} are Flyway version strings, null for a virgin schema. */
    public record SchemaResult(
            String schema, String from, String to, int applied, int pending, long millis, String error) {

        public boolean failed() {
            return error != null;
        }
    }

    /**
     * The thing the Job exits with. {@code platform} is null for a {@link #fanOut(Mode, List)} that was
     * called on its own.
     */
    public record Manifest(Mode mode, SchemaResult platform, List<SchemaResult> tenants, long millis) {

        public Manifest {
            tenants = List.copyOf(tenants);
        }

        public boolean ok() {
            return (platform == null || !platform.failed()) && tenants.stream().noneMatch(SchemaResult::failed);
        }

        public List<SchemaResult> failures() {
            var failed = new ArrayList<SchemaResult>();
            if (platform != null && platform.failed()) {
                failed.add(platform);
            }
            tenants.stream().filter(SchemaResult::failed).forEach(failed::add);
            return List.copyOf(failed);
        }

        /**
         * 0 clean, 1 some tenant is behind, 2 nothing could be attempted. Distinct because the runbook
         * responses differ: 1 is "read the manifest, fix that tenant, re-run"; 2 is "the fleet was never
         * touched, and the platform sequence or the database is why".
         */
        public int exitCode() {
            if (platform != null && platform.failed()) {
                return 2;
            }
            return ok() ? 0 : 1;
        }

        /** The manifest itself: one line per schema plus a summary. The Job's log is where it lands. */
        public List<String> report() {
            var lines = new ArrayList<String>();
            if (platform != null) {
                lines.add(line(platform));
            }
            tenants.forEach(result -> lines.add(line(result)));
            int failed = failures().size();
            lines.add("summary mode=%s tenants=%d ok=%d failed=%d ms=%d exit=%d".formatted(
                    mode, tenants.size(), tenants.size() - failed, failed, millis, exitCode()));
            return List.copyOf(lines);
        }

        private static String line(SchemaResult result) {
            return "schema=%s from=%s to=%s applied=%d pending=%d ms=%d status=%s%s".formatted(
                    result.schema(),
                    result.from() == null ? "-" : result.from(),
                    result.to() == null ? "-" : result.to(),
                    result.applied(),
                    result.pending(),
                    result.millis(),
                    result.failed() ? "FAILED" : "OK",
                    result.failed() ? " error=" + result.error() : "");
        }
    }

    /**
     * Everything, in order: platform, discovery, fan-out. What the Kubernetes Job and the test harness
     * both call.
     *
     * <p>A failed platform pass short-circuits the rest, and that is the one place this runner does
     * abort. The reason is not caution: {@code platform.tenant_placement} is created by that sequence, so
     * a run that continued past its failure would have nowhere to record what it found and no
     * trustworthy list of what to visit.
     */
    public Manifest run(Mode mode) {
        long started = System.nanoTime();
        SchemaResult platform = migratePlatform(mode);
        if (platform.failed()) {
            log.error("platform sequence failed — the tenant fan-out was not attempted: {}", platform.error());
            return new Manifest(mode, platform, List.of(), millisSince(started));
        }
        Manifest tenants = fanOut(mode, discoverFleet());
        return new Manifest(mode, platform, tenants.tenants(), millisSince(started));
    }

    /**
     * The platform sequence, on one schema, serially. Identical configuration to the {@code spring.flyway}
     * block the application boots with — deliberately, and asserted against {@code application.yaml} by
     * {@code MigrationScriptsTest}, because two Flyway configurations over one directory that disagree is
     * a way to apply a different set of migrations depending on who reached the database first.
     */
    public SchemaResult migratePlatform(Mode mode) {
        Flyway flyway = configure(platformScripts, PLATFORM_HISTORY_SCHEMA)
                // The one place createSchemas is true: on a fresh database nothing else creates these
                // four, and `public` is not among them (ADR 0010 §4.1 — depending on it is what would stop
                // a tenant being liftable to its own database).
                .schemas(PLATFORM_MANAGED_SCHEMAS.toArray(String[]::new))
                .createSchemas(true)
                .load();
        return apply(PLATFORM_HISTORY_SCHEMA, flyway, mode);
    }

    /**
     * {@code tenant_pool} first, then every silo — the catalogue's and the registry's, unioned.
     *
     * <p>The union is the interesting part. Catalogue-only would migrate a schema no tenant is placed in
     * and miss nothing; registry-only would skip a silo whose promotion died between {@code create schema}
     * and the placement flip, and that schema would then sit a migration behind forever with nothing to
     * notice. Taking both means a name in one and not the other is surfaced: a registry row with no schema
     * fails loudly in {@link #fanOut(Mode, List)}, and a schema with no row is migrated anyway.
     *
     * @throws IllegalStateException if a discovered name is not a legal tenant schema — every name here is
     *     interpolated into DDL and into {@code SET search_path}, so
     *     {@link TenantSchemas#requireSiloSchema} is the whole defence
     */
    public List<String> discoverFleet() {
        var found = new LinkedHashSet<String>();
        found.add(TenantSchemas.TENANT_POOL);
        try (Connection connection = dataSource.getConnection()) {
            found.addAll(query(connection, TENANT_SCHEMAS_IN_CATALOGUE));
            found.addAll(query(connection, SCHEMAS_IN_REGISTRY));
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Cannot enumerate the tenant fleet. platform.tenant_placement is created by the platform"
                            + " sequence, so this failing after a successful platform pass means the database"
                            + " moved underneath the run.", failure);
        }
        var ordered = new ArrayList<>(found);
        ordered.removeIf(schema -> schema == null || schema.isBlank());
        ordered.forEach(TenantMigrationRunner::requireTenantSchema);
        // tenant_pool first (ADR 0010 §4.2), then silos in a stable order so two runs of the manifest are
        // diffable.
        ordered.sort(Comparator.comparing((String schema) -> !TenantSchemas.TENANT_POOL.equals(schema))
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(ordered);
    }

    /**
     * The fan-out. One Flyway per schema across {@code workers} threads, every outcome recorded, the
     * registry updated per schema, and no failure allowed to stop another schema from being visited.
     *
     * <p>Public and separately callable so an operator can name one wedged silo
     * ({@code --schemas=t_… --mode=repair}) without touching the fleet, and so a test can drive it against
     * schemas of its own.
     */
    public Manifest fanOut(Mode mode, List<String> schemas) {
        schemas.forEach(TenantMigrationRunner::requireTenantSchema);
        long started = System.nanoTime();
        if (schemas.isEmpty()) {
            return new Manifest(mode, null, List.of(), millisSince(started));
        }

        Set<String> present = existingSchemas();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(workers, schemas.size()), namedThreads());
        try {
            List<Future<SchemaResult>> submitted = schemas.stream()
                    .map(schema -> pool.submit(() -> visit(schema, mode, present.contains(schema))))
                    .toList();
            var results = new ArrayList<SchemaResult>(submitted.size());
            for (Future<SchemaResult> future : submitted) {
                results.add(collect(future));
            }
            return new Manifest(mode, null, results, millisSince(started));
        } finally {
            pool.shutdown();
        }
    }

    /**
     * One schema, start to finish, on a worker thread. Catches everything a single tenant can throw,
     * because the alternative is a fleet-wide abort over one tenant — see the class note on
     * {@code SoftDeletePurgeJob}.
     */
    private SchemaResult visit(String schema, Mode mode, boolean exists) {
        long started = System.nanoTime();
        if (!exists) {
            // Reported rather than skipped: this is a placement row pointing at nothing, which means a
            // promotion died mid-flight or a schema was dropped without flipping its row. Either way that
            // tenant cannot be served and the manifest has to say so.
            return recordOutcome(new SchemaResult(schema, null, null, 0, 0, millisSince(started),
                    "schema does not exist — platform.tenant_placement names it but the catalogue does not"
                            + " hold it (a promotion that stopped before its schema was created, or a drop"
                            + " without a placement flip)"), mode);
        }
        try {
            Flyway flyway = configure(tenantScripts, schema)
                    .schemas(schema)
                    // Never true for a tenant: schema creation is the promoter's, under a freeze window and
                    // a runbook (ADR 0010 §4.3, §7 Phase 5). A migration runner that created schemas would
                    // turn a typo in a placement row into a new, empty, unserved tenant.
                    .createSchemas(false)
                    .load();
            return recordOutcome(apply(schema, flyway, mode), mode);
        } catch (RuntimeException failure) {
            // Thrown by load() rather than by migrate() — a schema that vanished between the catalogue
            // read and this borrow, a name Flyway refuses. Same reporting shape as apply()'s own catch.
            String stayedAt = historyHead(schema);
            return recordOutcome(
                    new SchemaResult(schema, stayedAt, stayedAt, 0, 0, millisSince(started), describe(failure)), mode);
        }
    }

    /** {@link Mode} applied to one already-configured Flyway. Shared by the platform pass and the fan-out. */
    private SchemaResult apply(String schema, Flyway flyway, Mode mode) {
        long started = System.nanoTime();
        if (mode == Mode.INFO) {
            MigrationInfoService info = flyway.info();
            MigrationInfo current = info.current();
            // MigrationInfo.getVersion() is a MigrationVersion, and MigrationVersion.getVersion() is null
            // for EMPTY — a schema with a history table and no versioned rows. Both nulls are the same
            // fact ("never migrated") and both have to survive the unwrapping.
            MigrationVersion version = current == null ? null : current.getVersion();
            String at = version == null ? null : version.getVersion();
            return new SchemaResult(schema, at, at, 0, info.pending().length, millisSince(started), null);
        }
        try {
            if (mode == Mode.REPAIR) {
                flyway.repair();
            }
            MigrateResult result = flyway.migrate();
            // targetSchemaVersion is null when nothing was applied (DbMigrate.getTargetVersion returns null
            // for an empty migration list), so the honest "where it ended up" is the initial version in
            // that case — not the empty string, which would read as version zero.
            String to = result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion;
            return new SchemaResult(
                    schema, result.initialSchemaVersion, to, result.migrationsExecuted, 0, millisSince(started), null);
        } catch (RuntimeException failure) {
            // The version has to come from the database now: migrate() threw, so there is no result to read
            // it off. With the transactional default this reads V(n−1) — the failed migration wrote no
            // history row at all — which is exactly the fact the version floor needs. `from` and `to` are
            // the same value and that is the report: a rolled-back migration moved the schema nowhere.
            String stayedAt = historyHead(schema);
            return new SchemaResult(schema, stayedAt, stayedAt, 0, 0, millisSince(started), describe(failure));
        }
    }

    /**
     * Writes the outcome into {@code platform.tenant_placement}. Never throws: a registry write that
     * failed must not turn a successful migration into a reported failure, nor stop the next schema. It
     * is logged at ERROR, which is the honest signal — the schema moved and the registry does not know —
     * and the head-parity test is what catches the drift.
     */
    private SchemaResult recordOutcome(SchemaResult result, Mode mode) {
        if (mode == Mode.INFO) {
            return result;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(result.failed() ? FAIL_PLACEMENT : CLEAR_PLACEMENT)) {
            statement.setString(1, result.to());
            if (result.failed()) {
                statement.setString(2, result.error());
                statement.setString(3, result.schema());
            } else {
                statement.setString(2, result.schema());
            }
            statement.executeUpdate();
        } catch (SQLException failure) {
            log.error("could not record placement for {} — the schema moved and the registry did not: {}",
                    result.schema(), failure.getMessage());
        }
        if (result.failed()) {
            log.error("tenant migration FAILED for {} (schema stays at {}): {}",
                    result.schema(), result.to() == null ? "an unread version" : result.to(), result.error());
        } else {
            log.info("tenant migration {} {} -> {} ({} applied, {} ms)",
                    result.schema(), result.from(), result.to(), result.applied(), result.millis());
        }
        return result;
    }

    /**
     * One Flyway, configured the way ADR 0010 §4.2 requires and no other way.
     *
     * <p>{@code locations} is still set even though both providers are supplied and the Scanner is
     * therefore never built. It is what the configuration <em>means</em>, and leaving it out would make a
     * future reader who drops a provider get an empty migration set rather than a slow one.
     */
    private FluentConfiguration configure(MigrationScripts scripts, String schema) {
        return Flyway.configure(MigrationScripts.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:" + scripts.location())
                // Both, always. Supplying one and not the other makes Flyway build the Scanner anyway to
                // fill the gap, and the Scanner is the 50–150 ms this runner exists to stop paying per
                // schema (ADR 0010 §4.2).
                .resourceProvider(scripts)
                .javaMigrationClassProvider(scripts)
                .defaultSchema(schema)
                .table("flyway_schema_history")
                // Validation is what catches an applied migration edited after the fact. It is also the
                // per-migrate re-checksum §4.2 measured — which MigrationScripts makes cheap by holding the
                // scripts in memory, rather than by switching the check off.
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                // Flyway's default, and load-bearing here: each migration commits in its own transaction,
                // so a failure at V(n) keeps V(n−1)'s history row and the schema is left at a real,
                // internally consistent version rather than rolled back to where the run started.
                .group(false)
                .outOfOrder(false)
                // Nothing this runner can be asked to do should ever be able to drop a tenant's tables.
                .cleanDisabled(true);
    }

    private String historyHead(String schema) {
        try (Connection connection = dataSource.getConnection()) {
            return historyHead(connection, schema);
        } catch (SQLException failure) {
            log.warn("could not read the history head of {}: {}", schema, failure.getMessage());
            return null;
        }
    }

    /**
     * The version a schema is actually at, read the way Flyway reads it: the last successfully applied
     * versioned migration by install order. Returns null when the history table is not there yet, which is
     * a schema that has never been migrated rather than an error.
     */
    public static String historyHead(Connection connection, String schema) throws SQLException {
        String qualified = quote(requireTenantOrPlatformSchema(schema)) + ".flyway_schema_history";
        try (PreparedStatement present = connection.prepareStatement("select to_regclass(?) is not null")) {
            present.setString(1, qualified);
            try (ResultSet found = present.executeQuery()) {
                if (!found.next() || !found.getBoolean(1)) {
                    return null;
                }
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select version from " + qualified
                        + " where success and version is not null order by installed_rank desc limit 1")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private Set<String> existingSchemas() {
        try (Connection connection = dataSource.getConnection()) {
            return Set.copyOf(query(connection, SCHEMAS_IN_CATALOGUE));
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot read the schema catalogue", failure);
        }
    }

    private static List<String> query(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    /**
     * {@code tenant_pool} or a silo, and nothing else. The name reaches {@code SET search_path} and DDL as
     * text — a {@code search_path} cannot be a bind parameter — so this is the whole defence, and it is
     * the same guard {@code TenantSchemas} applies on the request path.
     */
    private static String requireTenantSchema(String schema) {
        if (TenantSchemas.TENANT_POOL.equals(schema)) {
            return schema;
        }
        return TenantSchemas.requireSiloSchema(schema);
    }

    private static String requireTenantOrPlatformSchema(String schema) {
        return TenantSchemas.PLATFORM.equals(schema) ? schema : requireTenantSchema(schema);
    }

    /** Belt and braces on top of the regex above; the name is already known to be lower-case hex. */
    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static SchemaResult collect(Future<SchemaResult> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a tenant migration worker", interrupted);
        } catch (ExecutionException failure) {
            // visit() catches per-tenant failures itself, so reaching here means a worker died in a way the
            // fan-out cannot attribute to one tenant. Failing the run is right; silently dropping a schema
            // from the manifest is not.
            throw new IllegalStateException(
                    "A tenant migration worker died outside its own error handling", failure.getCause());
        }
    }

    private static ThreadFactory namedThreads() {
        var counter = new AtomicInteger();
        return runnable -> {
            // Platform threads, not virtual: the real bound is the JDBC pool, and named threads are what
            // make a stuck migration legible in a thread dump.
            Thread thread = new Thread(runnable, "tenant-migration-" + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    /**
     * The whole failure as one line, with the repetition taken out. Flyway wraps a
     * {@code FlywaySqlScriptException} in a {@code FlywayMigrateException} carrying the <em>same</em>
     * rendered message — the script, the SQLSTATE, the line — so a naive walk of the cause chain records
     * the same 600 characters three times and pushes the actual driver message past the truncation
     * point. Skipping a cause whose text the chain already carries is what keeps the useful part (the
     * {@code PSQLException} at the bottom) inside the registry column.
     */
    private static String describe(Throwable failure) {
        var text = new StringBuilder();
        for (Throwable cause = failure; cause != null && text.length() < MAX_RECORDED_ERROR; cause = cause.getCause()) {
            String message = truncate(String.valueOf(cause.getMessage()));
            if (!text.isEmpty()) {
                if (text.indexOf(message) >= 0) {
                    continue;
                }
                text.append(" | caused by: ");
            }
            text.append(cause.getClass().getSimpleName()).append(": ").append(message);
        }
        return truncate(text.toString());
    }

    private static String truncate(String text) {
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= MAX_RECORDED_ERROR ? flattened : flattened.substring(0, MAX_RECORDED_ERROR) + "…";
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * Sets a constant {@code search_path} on every borrow, for the reason {@code TenantRoutingDataSource}
     * sets a routed one: Flyway reads the connection's current path once per wrapped connection and
     * prepends its target schema to it, so a pooled connection a previous Flyway left pointing at another
     * tenant would give this one {@code t_b…, t_a…, ext} — a silo with another silo behind it on the
     * fall-through. Flyway does restore the original path on close; this is what makes the guarantee not
     * depend on that.
     */
    private static final class FixedSearchPathDataSource extends DelegatingDataSource {

        private final String searchPath;

        private FixedSearchPathDataSource(DataSource delegate, String searchPath) {
            super(delegate);
            this.searchPath = searchPath.toLowerCase(Locale.ROOT);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return pinned(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return pinned(super.getConnection(username, password));
        }

        private Connection pinned(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                // A constant, never a caller's string. On a fresh database `ext` does not exist yet and
                // Postgres accepts that quietly — a search_path entry naming no schema is ignored, and
                // `SHOW search_path` still answers, which is what keeps Flyway off its "Unable to determine
                // current schema as search_path is empty" path.
                statement.execute("SET search_path TO " + searchPath);
                return connection;
            } catch (SQLException failure) {
                closeQuietly(connection, failure);
                throw failure;
            }
        }

        private static void closeQuietly(Connection connection, SQLException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }
}
