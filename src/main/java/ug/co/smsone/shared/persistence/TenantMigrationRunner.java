package ug.co.smsone.shared.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;

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
 *       catalogue, plus every schema {@code platform.tenant_placement} names on the primary.</li>
 *   <li>{@link #fanOut(Mode, List)} — 8–16 workers, one Flyway per schema.</li>
 *   <li>Since ADR 0011, one further pass per named remote datasource, grouped: each remote's silos are
 *       discovered from ITS catalogue unioned with the primary registry's rows naming it, migrated over
 *       its own pool, and recorded — like everything else — in the primary registry, because
 *       {@code platform.tenant_placement} exists nowhere else. Never-abort spans the group boundary: an
 *       unreachable datasource fails its own tenants in the manifest and the run continues, and it does
 *       NOT touch the registry (see {@code remotePass}). What this runner still does not do on a remote
 *       is CREATE anything — destination-building for a cutover is the cutover machinery's
 *       (ADR 0011 §7.2 step 1), and a runner that created schemas would turn a typo'd placement row
 *       into a new, empty, unserved tenant.</li>
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
     * <strong>The row this pass does not own, and therefore does not touch — in either direction.</strong>
     *
     * <p>Two different writers produce a {@code FAILED} placement and they mean different things.
     * <em>This</em> runner marks a SCHEMA that would not migrate: every tenant living in it was serving
     * a moment ago and will serve again as soon as a later pass succeeds, which is what
     * {@link #CLEAR_PLACEMENT}'s rescue is for. {@code TenantPlacements.markFailed} marks a TENANT whose
     * schema was never built — a signup whose Flyway pass failed — and that row carries an extra meaning
     * this runner cannot see: <strong>never announced</strong>. Rescuing one of those to {@code ACTIVE}
     * is not a repair, it is a permanent wedge: {@code TenantPlacements.announce} only publishes
     * {@code OrganizationRegistered} for the call that transitions a row INTO {@code ACTIVE}, so a row
     * already sitting there can never be announced, and that tenant has no trial, no billing account and
     * no search document forever, behind a registry row that reads perfectly healthy.
     *
     * <p><strong>The shipped default is what made this load-bearing.</strong> Under
     * {@code PlacementPolicy.POOLED} a signup ran no DDL at all — a pooled tenant goes straight to
     * {@code ACTIVE} in {@code announce}, and this shape could not exist. Since {@code silo-per-org}
     * became the default, EVERY signup creates a schema and runs a fresh tenant sequence, so one transient
     * lock, one full disk or one concurrent DDL produces exactly this row on the ordinary signup path.
     *
     * <p><strong>{@code schema_version} is the discriminator</strong> because it is the one column whose
     * null the two writers do not share. {@code reserve} inserts the row with a null version and only
     * {@code recordMigrated} stamps one — AFTER a successful migrate — so a provisioning failure is
     * always {@code FAILED} with no version, while a schema this runner failed on is at a real version it
     * reads back and writes (see {@link #FAIL_PLACEMENT}).
     *
     * <p><strong>And it is on both statements, which is the part a rescue-only guard gets wrong.</strong>
     * Guarding only the rescue holds until the next FAILING pass over that schema stamps a version onto
     * the row — and from then on it looks exactly like one of this runner's own, so the pass after that
     * rescues it and the wedge is back, one release later. Excluding the row from both writes keeps the
     * marker durable: nothing but provisioning's retry, or {@code announce} itself, moves it.
     *
     * <p>The cost is one shape this pass will not heal: a tenant marked {@code FAILED} with no recorded
     * version stays in the unhealthy-fleet query until something announces it. That is the honest reading
     * — nothing has proved that tenant was ever announced — and it is loud rather than silent.
     */
    private static final String NOT_A_PROVISIONING_FAILURE =
            " and not (state = 'FAILED' and schema_version is null)";

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
     *
     * <p>{@link #NOT_A_PROVISIONING_FAILURE} is what "itself put there" is decided by. Read it before
     * changing either statement; it is the difference between a repair and a tenant that can never be
     * announced.
     */
    private static final String CLEAR_PLACEMENT =
            "update platform.tenant_placement"
                    + " set schema_version = ?,"
                    + "     last_error = null,"
                    + "     state = case when state = 'FAILED' then 'ACTIVE' else state end,"
                    + "     updated_at = now()"
                    // The datasource arm exists for one window (ADR 0011 §7.2 step 10): during a
                    // cutover's watch the SOURCE schema still exists on primary while the row already
                    // names the destination, and a primary pass over the leftover must not stamp the
                    // serving row with the abandoned copy's version. Keyed on both, the write lands
                    // only on the copy this pass actually migrated.
                    + " where schema_name = ? and datasource_name = ?"
                    + NOT_A_PROVISIONING_FAILURE;

    /**
     * {@code schema_version} is written on the failure path too, and that is the point of it. A
     * transactional migration that fails rolls back whole, so the schema really is at V(n−1) and the
     * compiled-in version floor (§4.4) has to be able to read that as a fact rather than infer it from
     * the absence of a value.
     *
     * <p><strong>{@code coalesce} rather than a plain assignment</strong>, for the reason V57's column
     * note states: NULL there means "not yet recorded", never "behind". {@link #historyHead(String)}
     * answers null for a schema that has never been migrated AND for a head it could not read — it
     * catches the {@code SQLException} and warns — so a plain write would let one unlucky read erase a
     * version the floor check depends on, and would turn a serving tenant's row into the
     * FAILED-with-no-version shape {@link #NOT_A_PROVISIONING_FAILURE} then refuses to rescue.
     */
    private static final String FAIL_PLACEMENT =
            "update platform.tenant_placement"
                    + " set schema_version = coalesce(?, schema_version),"
                    + "     last_error = ?,"
                    + "     state = 'FAILED',"
                    + "     updated_at = now()"
                    // Same both-keys rule as CLEAR_PLACEMENT, and here it is the sharper half: a
                    // failure on a leftover source schema marking the row that serves from the
                    // DESTINATION as FAILED would stand a healthy tenant's fan-outs down over a copy
                    // nobody serves from.
                    + " where schema_name = ? and datasource_name = ?"
                    + NOT_A_PROVISIONING_FAILURE;

    /** Silos are named from {@code organization.id}, so the catalogue can be filtered without the registry. */
    private static final String TENANT_SCHEMAS_IN_CATALOGUE =
            "select schema_name from information_schema.schemata"
                    + " where schema_name = 'tenant_pool' or schema_name ~ '^t_[0-9a-f]{32}$'";

    private static final String SCHEMAS_IN_CATALOGUE = "select schema_name from information_schema.schemata";

    /**
     * Filtered by datasource since ADR 0011: the registry names every database's schemas, and handing a
     * remote-placed silo to the PRIMARY fan-out would report it "schema does not exist" and mark a
     * healthy remote tenant FAILED — the availability coupling §4.2 exists to prevent, produced by the
     * migration job itself.
     */
    private static final String SCHEMAS_IN_REGISTRY =
            "select distinct schema_name from platform.tenant_placement where datasource_name = ?";

    /**
     * Only silo-shaped names on a remote, deliberately no {@code tenant_pool} arm: a remote database
     * holds silos and the minimal platform schema (ADR 0011 §5.1), and a stray {@code tenant_pool}
     * there is somebody's mistake — migrating it would give the mistake a version and a future.
     */
    private static final String SILOS_IN_CATALOGUE =
            "select schema_name from information_schema.schemata where schema_name ~ '^t_[0-9a-f]{32}$'";

    private final DataSource dataSource;
    private final Map<String, DataSource> remotes;
    private final MigrationScripts platformScripts;
    private final MigrationScripts tenantScripts;
    private final int workers;

    /**
     * @param dataSource wrapped so every borrow starts from {@link #BASELINE_SEARCH_PATH}; it must be
     *     able to supply {@link #connectionsRequired(int)} connections or the fan-out deadlocks on itself
     */
    public TenantMigrationRunner(
            DataSource dataSource, MigrationScripts platformScripts, MigrationScripts tenantScripts, int workers) {
        this(dataSource, Map.of(), platformScripts, tenantScripts, workers);
    }

    /**
     * The ADR 0011 shape: the primary plus every named remote datasource ({@code app.tenancy
     * .datasources.*} — the same names {@code tenant_placement.datasource_name} selects). Each remote
     * gets the same baseline-{@code search_path} wrapper as the primary and its own per-datasource pass
     * in {@link #run}; the registry is written on PRIMARY regardless, because
     * {@code platform.tenant_placement} exists nowhere else.
     */
    public TenantMigrationRunner(DataSource dataSource, Map<String, DataSource> remoteDataSources,
            MigrationScripts platformScripts, MigrationScripts tenantScripts, int workers) {
        if (workers < 1 || workers > MAX_WORKERS) {
            throw new IllegalArgumentException(
                    "workers must be between 1 and " + MAX_WORKERS + " (ADR 0010 §4.2 measured 8–16), was " + workers);
        }
        this.dataSource = new FixedSearchPathDataSource(dataSource, BASELINE_SEARCH_PATH);
        Map<String, DataSource> wrapped = new LinkedHashMap<>();
        remoteDataSources.forEach((name, remote) ->
                wrapped.put(name, new FixedSearchPathDataSource(remote, BASELINE_SEARCH_PATH)));
        // Not Map.copyOf, which forgets insertion order: the per-datasource passes run in the order
        // the caller declared, so two runs of the same fleet produce a diffable manifest.
        this.remotes = java.util.Collections.unmodifiableMap(wrapped);
        this.platformScripts = platformScripts;
        this.tenantScripts = tenantScripts;
        this.workers = workers;
    }

    /** The production wiring: both sequences off the classpath, the tenant one refusing script config. */
    public static TenantMigrationRunner fromClasspath(DataSource dataSource, int workers) {
        return fromClasspath(dataSource, Map.of(), workers);
    }

    /** {@link #fromClasspath(DataSource, int)} plus the named remote datasources (ADR 0011 §4.3). */
    public static TenantMigrationRunner fromClasspath(
            DataSource dataSource, Map<String, DataSource> remoteDataSources, int workers) {
        return new TenantMigrationRunner(
                dataSource,
                remoteDataSources,
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

    /**
     * One schema's outcome, on one datasource. {@code from}/{@code to} are Flyway version strings, null
     * for a virgin schema. {@code datasource} was added by ADR 0011 as the LEADING component so the
     * pre-existing seven-argument construction order kept compiling unchanged through the secondary
     * constructor below — a positional caller silently binding {@code schema} to {@code datasource}
     * is the record-evolution bug that ordering rules out.
     */
    public record SchemaResult(String datasource, String schema, String from, String to, int applied,
            int pending, long millis, String error) {

        /** The pre-ADR-0011 shape: an outcome on the platform database. */
        public SchemaResult(
                String schema, String from, String to, int applied, int pending, long millis, String error) {
            this(TenantPlacement.PRIMARY_DATASOURCE, schema, from, to, applied, pending, millis, error);
        }

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
            return "schema=%s datasource=%s from=%s to=%s applied=%d pending=%d ms=%d status=%s%s".formatted(
                    result.schema(),
                    result.datasource(),
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
        var results = new ArrayList<>(fanOut(mode, discoverFleet()).tenants());
        // One pass per named datasource, after primary's — grouped, exactly the way TenantFanOut
        // orders a sweep, and for the same reason: a remote's failures cluster into one contiguous
        // stretch of the manifest, and its connection load is its own. Never-abort holds across the
        // group boundary too: an unreachable datasource fails ITS tenants in the manifest and the run
        // continues to the next one.
        remotes.forEach((name, pool) -> results.addAll(remotePass(mode, name, pool)));
        return new Manifest(mode, platform, results, millisSince(started));
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
            found.addAll(registrySchemas(connection, TenantPlacement.PRIMARY_DATASOURCE));
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Cannot enumerate the tenant fleet. platform.tenant_placement is created by the platform"
                            + " sequence, so this failing after a successful platform pass means the database"
                            + " moved underneath the run.", failure);
        }
        return orderedFleet(found);
    }

    private static List<String> orderedFleet(LinkedHashSet<String> found) {
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
     * One remote datasource, start to finish, never aborting the run (the class's own doctrine, one
     * level up). The one failure with no per-schema shape — the datasource itself unreachable — fails
     * every registry-known schema in the MANIFEST and touches the registry not at all: the state of
     * those schemas is UNKNOWN, not proven unfit, and a FAILED row would stand a serving tenant's
     * fan-outs down (ADR 0010 §8 Q7 says serve and page) over what may be the migration job's own
     * network path. The next reachable pass writes the truth either way.
     */
    private List<SchemaResult> remotePass(Mode mode, String datasourceName, DataSource pool) {
        long started = System.nanoTime();
        // The registry half first, on PRIMARY, unguarded: primary just served the platform pass, so
        // losing it mid-run is the database moving underneath us — discoverFleet's own abort case, not
        // a remote's failure to survive.
        List<String> fromRegistry;
        try (Connection connection = dataSource.getConnection()) {
            fromRegistry = registrySchemas(connection, datasourceName);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Cannot enumerate datasource '" + datasourceName + "' from platform.tenant_placement"
                            + " after a successful platform pass — the primary database moved underneath"
                            + " the run.", failure);
        }
        List<String> discovered;
        try {
            var found = new LinkedHashSet<>(fromRegistry);
            try (Connection connection = pool.getConnection()) {
                found.addAll(query(connection, SILOS_IN_CATALOGUE));
            }
            discovered = orderedFleet(found);
        } catch (SQLException | RuntimeException unreachable) {
            long millis = millisSince(started);
            if (fromRegistry.isEmpty()) {
                // Config leading placement by a release is legitimate (ADR 0011 §4.2); an unreachable
                // datasource nobody is placed on holds no tenant behind, so it must not fail the run.
                log.warn("datasource '{}' is unreachable and no placement row names it — nothing to"
                        + " migrate there, continuing: {}", datasourceName, describe(unreachable));
                return List.of();
            }
            // Manifest failures, registry untouched: those schemas' state is UNKNOWN, not proven
            // unfit, and a FAILED row would stand a serving tenant's fan-outs down (ADR 0010 §8 Q7
            // says serve and page) over what may be the migration job's own network path.
            log.error("datasource '{}' is unreachable — its {} tenant schema(s) were not attempted and"
                    + " the registry was left alone: {}",
                    datasourceName, fromRegistry.size(), describe(unreachable));
            return fromRegistry.stream()
                    .map(TenantMigrationRunner::requireTenantSchema)
                    .map(schema -> new SchemaResult(datasourceName, schema, null, null, 0, 0, millis,
                            "datasource '" + datasourceName + "' unreachable: " + describe(unreachable)))
                    .toList();
        }
        return fanOutOn(mode, datasourceName, pool, discovered).tenants();
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
        return fanOutOn(mode, TenantPlacement.PRIMARY_DATASOURCE, dataSource, schemas);
    }

    /**
     * The fan-out proper, on one datasource. The worker count is additionally capped by what the pool
     * can actually lend — Flyway holds two connections per instance, so a remote pool of Hikari's
     * default shape would deadlock under the primary's worker count, every worker holding one
     * connection and waiting for a second only another waiting worker can free.
     */
    private Manifest fanOutOn(Mode mode, String datasourceName, DataSource pool, List<String> schemas) {
        schemas.forEach(TenantMigrationRunner::requireTenantSchema);
        long started = System.nanoTime();
        if (schemas.isEmpty()) {
            return new Manifest(mode, null, List.of(), millisSince(started));
        }

        Set<String> present = existingSchemas(pool);
        int concurrency = Math.min(Math.min(workers, schemas.size()), workersAffordedBy(pool));
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, namedThreads());
        try {
            List<Future<SchemaResult>> submitted = schemas.stream()
                    .map(schema -> executor.submit(
                            () -> visit(datasourceName, pool, schema, mode, present.contains(schema))))
                    .toList();
            var results = new ArrayList<SchemaResult>(submitted.size());
            for (Future<SchemaResult> future : submitted) {
                results.add(collect(future));
            }
            return new Manifest(mode, null, results, millisSince(started));
        } finally {
            executor.shutdown();
        }
    }

    /**
     * How many Flyway workers a pool can serve without deadlocking on itself — the
     * {@link #connectionsRequired(int)} arithmetic inverted, for the pools this runner did NOT size.
     * The primary was sized by its caller to fit {@code workers}; a remote pool arrives with whatever
     * {@code app.tenancy.datasources.<name>.pool.maximum-pool-size} says (default 8), and unwrapping to
     * Hikari is the only way to ask it.
     */
    private int workersAffordedBy(DataSource pool) {
        DataSource unwrapped = pool;
        while (unwrapped instanceof DelegatingDataSource delegating) {
            unwrapped = delegating.getTargetDataSource();
        }
        if (unwrapped instanceof HikariDataSource hikari) {
            return Math.max(1, (hikari.getMaximumPoolSize() - 2) / 2);
        }
        return workers;
    }

    /**
     * One schema, start to finish, on a worker thread. Catches everything a single tenant can throw,
     * because the alternative is a fleet-wide abort over one tenant — see the class note on
     * {@code SoftDeletePurgeJob}.
     */
    private SchemaResult visit(String datasourceName, DataSource pool, String schema, Mode mode, boolean exists) {
        long started = System.nanoTime();
        if (mode != Mode.INFO && cutoverInFlight(schema)) {
            // ADR 0011 §7.3, measured: DDL in a schema under a live publication crash-loops the
            // subscriber's apply worker every ~5 s, freezes confirmed_flush_lsn and grows WAL on the
            // publisher — and the obvious "heal" (create the table on the destination by hand) then
            // SILENTLY SKIPS that table's rows until ALTER SUBSCRIPTION … REFRESH PUBLICATION
            // tablesyncs it. Reported as a failure (non-zero exit, so a release cannot roll past a
            // tenant it did not migrate) but DELIBERATELY not written to the registry: the tenant is
            // serving and healthy, and a FAILED placement would stand its background work down over a
            // refusal that is this runner's, not the schema's.
            String stayedAt = historyHead(pool, schema);
            log.error("tenant migration REFUSED for {} on {}: a cutover is in flight for this schema"
                    + " (platform.tenant_cutover). Finish, roll back or abort the cutover first; if a"
                    + " migration already reached a published schema, repair per"
                    + " docs/runbooks/tenant-cutover.md (migrate the destination, then REFRESH"
                    + " PUBLICATION).", schema, datasourceName);
            return new SchemaResult(datasourceName, schema, stayedAt, stayedAt, 0, 0,
                    millisSince(started),
                    "refused: a cutover is in flight for this schema (platform.tenant_cutover;"
                            + " ADR 0011 §7.3 — DDL under a live publication breaks the stream, and the"
                            + " hand-heal then under-copies silently)");
        }
        if (!exists) {
            // Reported rather than skipped: this is a placement row pointing at nothing, which means a
            // promotion died mid-flight, a schema was dropped without flipping its row, or — since ADR
            // 0011 — a cutover's destination was never built on the datasource its row already names.
            // Either way that tenant cannot be served and the manifest has to say so.
            return recordOutcome(new SchemaResult(datasourceName, schema, null, null, 0, 0, millisSince(started),
                    "schema does not exist — platform.tenant_placement names it but the catalogue does not"
                            + " hold it (a promotion that stopped before its schema was created, or a drop"
                            + " without a placement flip)"), mode);
        }
        try {
            Flyway flyway = configure(tenantScripts, schema, pool)
                    .schemas(schema)
                    // Never true for a tenant: schema creation is the promoter's, under a freeze window and
                    // a runbook (ADR 0010 §4.3, §7 Phase 5). A migration runner that created schemas would
                    // turn a typo in a placement row into a new, empty, unserved tenant.
                    .createSchemas(false)
                    .load();
            return recordOutcome(withDatasource(datasourceName, apply(schema, flyway, mode, pool)), mode);
        } catch (RuntimeException failure) {
            // Thrown by load() rather than by migrate() — a schema that vanished between the catalogue
            // read and this borrow, a name Flyway refuses. Same reporting shape as apply()'s own catch.
            String stayedAt = historyHead(pool, schema);
            return recordOutcome(new SchemaResult(datasourceName, schema, stayedAt, stayedAt, 0, 0,
                    millisSince(started), describe(failure)), mode);
        }
    }

    /** Re-labels an outcome with the datasource its Flyway actually ran against. */
    private static SchemaResult withDatasource(String datasourceName, SchemaResult result) {
        return new SchemaResult(datasourceName, result.schema(), result.from(), result.to(),
                result.applied(), result.pending(), result.millis(), result.error());
    }

    /** {@link Mode} applied to one already-configured Flyway. Shared by the platform pass and the fan-out. */
    private SchemaResult apply(String schema, Flyway flyway, Mode mode) {
        return apply(schema, flyway, mode, dataSource);
    }

    private SchemaResult apply(String schema, Flyway flyway, Mode mode, DataSource pool) {
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
            String stayedAt = historyHead(pool, schema);
            return new SchemaResult(schema, stayedAt, stayedAt, 0, 0, millisSince(started), describe(failure));
        }
    }

    /**
     * Writes the outcome into {@code platform.tenant_placement} — always on PRIMARY, whichever
     * datasource the Flyway ran against, because the registry exists nowhere else (ADR 0011 §5). Never
     * throws: a registry write that failed must not turn a successful migration into a reported
     * failure, nor stop the next schema. It is logged at ERROR, which is the honest signal — the schema
     * moved and the registry does not know — and the head-parity test is what catches the drift.
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
                statement.setString(4, result.datasource());
            } else {
                statement.setString(2, result.schema());
                statement.setString(3, result.datasource());
            }
            statement.executeUpdate();
        } catch (SQLException failure) {
            log.error("could not record placement for {} — the schema moved and the registry did not: {}",
                    result.schema(), failure.getMessage());
        }
        if (result.failed()) {
            log.error("tenant migration FAILED for {} on '{}' (schema stays at {}): {}",
                    result.schema(), result.datasource(),
                    result.to() == null ? "an unread version" : result.to(), result.error());
        } else {
            log.info("tenant migration {} on '{}' {} -> {} ({} applied, {} ms)",
                    result.schema(), result.datasource(), result.from(), result.to(),
                    result.applied(), result.millis());
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
        return configure(scripts, schema, dataSource);
    }

    private FluentConfiguration configure(MigrationScripts scripts, String schema, DataSource pool) {
        return Flyway.configure(MigrationScripts.class.getClassLoader())
                .dataSource(pool)
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

    /**
     * Whether {@code platform.tenant_cutover} (V60) holds a live row for this schema — asked of
     * PRIMARY always, because the cutover registry lives nowhere else. {@code to_regclass}-guarded:
     * this runner also runs against scratch databases mid-build, where the platform sequence may not
     * have reached V60 yet, and "the table is not there" honestly means "no cutover can be in flight".
     */
    private boolean cutoverInFlight(String schema) {
        try (Connection connection = dataSource.getConnection()) {
            // Two statements, not one: a single query naming the table would fail to PARSE on a
            // pre-V60 database — the planner resolves every relation before any boolean shortcuts —
            // and the catch below would then refuse every schema on a database that has no cutovers.
            try (PreparedStatement present = connection.prepareStatement(
                    "select to_regclass('platform.tenant_cutover') is not null")) {
                try (ResultSet found = present.executeQuery()) {
                    if (!found.next() || !found.getBoolean(1)) {
                        return false;
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "select exists (select 1 from platform.tenant_cutover where schema_name = ?)")) {
                statement.setString(1, schema);
                try (ResultSet found = statement.executeQuery()) {
                    return found.next() && found.getBoolean(1);
                }
            }
        } catch (SQLException failure) {
            // Fail toward refusing the migration, not toward running it: the cost of a wrong refusal
            // is a re-run, the cost of a wrong pass is the measured §7.3 breakage.
            log.warn("could not read platform.tenant_cutover for {} — refusing this schema's pass"
                    + " rather than risking DDL under a live publication: {}", schema, failure.getMessage());
            return true;
        }
    }

    private String historyHead(DataSource pool, String schema) {
        try (Connection connection = pool.getConnection()) {
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

    private static Set<String> existingSchemas(DataSource pool) {
        try (Connection connection = pool.getConnection()) {
            return Set.copyOf(query(connection, SCHEMAS_IN_CATALOGUE));
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot read the schema catalogue", failure);
        }
    }

    private static List<String> registrySchemas(Connection connection, String datasourceName)
            throws SQLException {
        var values = new ArrayList<String>();
        try (PreparedStatement statement = connection.prepareStatement(SCHEMAS_IN_REGISTRY)) {
            statement.setString(1, datasourceName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
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
