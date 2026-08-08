package ug.co.smsone.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Manifest;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Mode;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.SchemaResult;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * ADR 0010 §7 Phase 4's gate, against a real Postgres.
 *
 * <p><b>No Spring context, on purpose.</b> The subject is a plain object over a {@link javax.sql.DataSource}
 * that runs in a Kubernetes Job with none of the application around it, and a test that booted the
 * application to exercise it would be testing a wiring that does not exist in production. It borrows
 * {@code AbstractIntegrationTest.POSTGRES} — which also means touching this class runs
 * {@code TenantSchemaBootstrap}, so the database these assertions run against was itself built by the
 * runner.
 *
 * <p><b>The failure cases use a fixture sequence, not a real migration.</b> A deliberately failing
 * migration in {@code db/migration/tenant} would fail every deploy forever. The runner takes its script
 * set as a constructor parameter precisely so the failure path can be exercised without one — see
 * {@code src/test/resources/tenant-migration-fixtures/}.
 */
class TenantMigrationRunnerTest {

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    /**
     * The version the tenant sequence ends at, derived from the files rather than written down twice.
     * Declared after {@link #VERSION} deliberately: static initializers run in source order, and a
     * constant that read a field declared below it would silently see null.
     */
    private static final String TENANT_HEAD = headOfTenantSequence();

    private static HikariDataSource pool;

    private final List<String> scratchSchemas = new ArrayList<>();
    private final List<UUID> scratchPlacements = new ArrayList<>();

    @BeforeAll
    static void openPool() {
        var config = new HikariConfig();
        config.setPoolName("tenant-migration-test");
        config.setJdbcUrl(AbstractIntegrationTest.POSTGRES.getJdbcUrl());
        config.setUsername(AbstractIntegrationTest.POSTGRES.getUsername());
        config.setPassword(AbstractIntegrationTest.POSTGRES.getPassword());
        // Sized from the runner's own arithmetic and not from a number chosen here. That makes the
        // parallel test below a real check of connectionsRequired(): get it wrong and the fan-out hangs
        // with every worker holding one connection and waiting for its second.
        config.setMaximumPoolSize(TenantMigrationRunner.connectionsRequired(4));
        pool = new HikariDataSource(config);
    }

    @AfterAll
    static void closePool() {
        // Null-guarded: if openPool() failed, an NPE here becomes the reported failure and buries the
        // one that matters.
        if (pool != null) {
            pool.close();
        }
    }

    /**
     * Scratch schemas and registry rows go away even when an assertion fails — the head-parity test
     * reads every tenant schema in the database, and a silo this class deliberately left one migration
     * behind would fail it from the outside.
     */
    @AfterEach
    void dropScratch() {
        try (Connection connection = pool.getConnection()) {
            for (String schema : scratchSchemas) {
                // One schema per transaction, always. `drop schema cascade` takes ~439 lockable relations
                // per tenant schema against ~6,400 slots, so fifteen in one transaction is the measured
                // ceiling and the sixteenth takes the whole block down with "out of shared memory"
                // (ADR 0010 §5.12). Autocommit here means one per transaction by construction.
                execute(connection, "drop schema if exists \"" + schema + "\" cascade");
            }
            for (UUID orgId : scratchPlacements) {
                try (PreparedStatement statement =
                        connection.prepareStatement("delete from platform.tenant_placement where org_id = ?")) {
                    statement.setObject(1, orgId);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not clean up after a tenant migration test", failure);
        }
        scratchSchemas.clear();
        scratchPlacements.clear();
    }

    /**
     * THE GATE, first half: the runner applies the tenant sequence to {@code tenant_pool} and the
     * registry's {@code schema_version} reflects it.
     *
     * <p>It is a re-run — {@code TenantSchemaBootstrap} already migrated the pool — which is the more
     * interesting assertion of the two: an idempotent pass with nothing pending must still record the
     * version, or the first deploy after a release with no tenant migrations would blank it and 503 the
     * fleet against its own floor.
     */
    @Test
    void theTenantSequenceLandsInTenantPoolAndTheRegistryRecordsIt() {
        UUID pooledTenant = placementFor(TenantSchemas.TENANT_POOL, "ACTIVE");

        Manifest manifest = runner(realTenantScripts(), 1).fanOut(Mode.MIGRATE, List.of(TenantSchemas.TENANT_POOL));

        assertThat(manifest.ok()).describedAs(manifest.report().toString()).isTrue();
        assertThat(manifest.exitCode()).isZero();
        assertThat(historyHead(TenantSchemas.TENANT_POOL)).isEqualTo(TENANT_HEAD);
        assertThat(only(manifest).to())
                .describedAs("a pass with nothing pending still has to report where the schema IS")
                .isEqualTo(TENANT_HEAD);
        assertThat(placementVersion(pooledTenant)).isEqualTo(TENANT_HEAD);
        assertThat(placementState(pooledTenant)).isEqualTo("ACTIVE");
    }

    /**
     * THE GATE, second half: a deliberately failing tenant migration leaves that schema at the previous
     * version with <strong>no</strong> history row, marks the registry FAILED, and does not stop the
     * runner.
     *
     * <p>One worker, and the doomed schema first in the list. That is what makes "does not stop the
     * runner" falsifiable: with a single thread the healthy schema is visited strictly after the failure,
     * so a runner that aborted would leave it untouched and this test would see it.
     */
    @Test
    void aFailingTenantMigrationStopsAtThatSchemaAndNowhereElse() {
        String doomed = scratchSilo();
        String healthy = scratchSilo();
        UUID doomedTenant = placementFor(doomed, "ACTIVE");
        UUID healthyTenant = placementFor(healthy, "ACTIVE");
        // Release N first, so both schemas have a PREVIOUS version to be left at. Without it Flyway
        // never reaches V9002: it refuses an unbaselined non-empty schema outright ("Found non-empty
        // schema(s) … but no schema history table"), and the test would be asserting on a schema at no
        // version at all rather than on the rollback.
        runner(releaseN(), 1).fanOut(Mode.MIGRATE, List.of(doomed, healthy));
        assertThat(historyHead(doomed)).isEqualTo("9001");
        // The failure is environmental: V9002 creates fixture_wedge, so a schema that already has one
        // fails on that statement and a schema that does not succeeds. Same scripts, both schemas.
        execute("create table \"" + doomed + "\".fixture_wedge (id int primary key)");

        Manifest manifest = runner(releaseNPlusOne(), 1).fanOut(Mode.MIGRATE, List.of(doomed, healthy));

        assertThat(manifest.tenants()).hasSize(2);
        assertThat(manifest.exitCode()).isEqualTo(1);
        assertThat(manifest.failures()).singleElement()
                .extracting(SchemaResult::schema).isEqualTo(doomed);

        // The schema sits at V(n-1), internally consistent.
        assertThat(historyHead(doomed)).isEqualTo("9001");
        assertThat(historyRows(doomed, "9002"))
                .describedAs("a transactional failure writes NO history row — success=false is the"
                        + " non-transactional case ADR 0010 §4.2 forbids, and the whole reason it does")
                .isZero();
        assertThat(relationExists(doomed, "fixture_first"))
                .describedAs("the migration BEFORE the failure keeps its work")
                .isTrue();
        assertThat(relationExists(doomed, "fixture_boom"))
                .describedAs("the failing migration's own earlier statements rolled back with it")
                .isFalse();

        // And the run continued past it.
        assertThat(historyHead(healthy)).isEqualTo("9002");
        assertThat(relationExists(healthy, "fixture_boom")).isTrue();

        // The registry says so, which is the point of it: partial-fleet state as a queryable fact.
        assertThat(placementState(doomedTenant)).isEqualTo("FAILED");
        assertThat(placementError(doomedTenant)).contains("fixture_wedge");
        assertThat(placementVersion(doomedTenant))
                .describedAs("the floor check needs the version the schema is ACTUALLY at, not a null")
                .isEqualTo("9001");
        assertThat(placementState(healthyTenant)).isEqualTo("ACTIVE");
        assertThat(placementError(healthyTenant)).isNull();
        assertThat(placementVersion(healthyTenant)).isEqualTo("9002");
    }

    /**
     * Repair mode, against the exact row a non-transactional failure leaves behind.
     *
     * <p>The wedge cannot be produced by a transactional migration — that is the argument for forbidding
     * {@code executeInTransaction=false} in the first place — so the test writes the {@code success =
     * false} history row itself, which is precisely what {@code DbMigrate.applyMigrations} writes when
     * the migration was not in a transaction. From there the behaviour is Flyway's: MIGRATE refuses to
     * touch a schema with a failed migration in its history, and REPAIR clears it and finishes the job.
     */
    @Test
    void repairClearsAWedgedSchemaAndThenFinishesTheMigration() {
        String wedged = scratchSilo();
        UUID tenant = placementFor(wedged, "ACTIVE");
        runner(releaseN(), 1).fanOut(Mode.MIGRATE, List.of(wedged));
        execute("create table \"" + wedged + "\".fixture_wedge (id int primary key)");
        runner(releaseNPlusOne(), 1).fanOut(Mode.MIGRATE, List.of(wedged));
        wedgeHistory(wedged);

        Manifest refused = runner(releaseNPlusOne(), 1).fanOut(Mode.MIGRATE, List.of(wedged));

        assertThat(only(refused).failed())
                .describedAs("Flyway refuses to migrate past a failed history row — this is the state one"
                        + " flaky CONCURRENTLY would leave in every silo it touched")
                .isTrue();

        // Clear what made it fail, then repair.
        execute("drop table \"" + wedged + "\".fixture_wedge");
        Manifest repaired = runner(releaseNPlusOne(), 1).fanOut(Mode.REPAIR, List.of(wedged));

        assertThat(repaired.ok()).describedAs(repaired.report().toString()).isTrue();
        assertThat(historyHead(wedged))
                .describedAs("repair alone would leave the schema unwedged and still behind; the mode"
                        + " migrates afterwards for exactly that reason")
                .isEqualTo("9002");
        assertThat(historyRows(wedged, "9002")).isEqualTo(1);
        assertThat(placementState(tenant))
                .describedAs("a recovered schema comes back out of FAILED, or nothing ever leaves the"
                        + " unhealthy-fleet query")
                .isEqualTo("ACTIVE");
        assertThat(placementError(tenant)).isNull();
    }

    /**
     * The fan-out is parallel and the connection budget survives it. Flyway holds two connections per
     * schema, so this is the shape that deadlocks when {@code connectionsRequired} is wrong — four
     * workers against a pool sized by that method, migrating four fresh silos plus the pool.
     *
     * <p>It is also the assertion that parallel tenant migration does not serialize on Flyway's advisory
     * lock: the discriminator is {@code "<schema>"."flyway_schema_history"}.hashCode(), so four schemas
     * take four different locks. A single shared lock would still pass this test — it would just be
     * slow — which is why the claim is documented against Flyway's source rather than timed here.
     */
    @Test
    void fourWorkersMigrateFourFreshSilosAndThePoolInOnePass() {
        List<String> silos = List.of(scratchSilo(), scratchSilo(), scratchSilo(), scratchSilo());
        var everything = new ArrayList<String>();
        everything.add(TenantSchemas.TENANT_POOL);
        everything.addAll(silos);

        Manifest manifest = runner(realTenantScripts(), 4).fanOut(Mode.MIGRATE, everything);

        assertThat(manifest.ok()).describedAs(manifest.report().toString()).isTrue();
        assertThat(manifest.tenants()).hasSize(5);
        assertThat(manifest.tenants()).allSatisfy(result ->
                assertThat(result.to()).isEqualTo(TENANT_HEAD));
        assertThat(silos).allSatisfy(silo ->
                assertThat(historyHead(silo))
                        .describedAs("%s must reach the same head as the pool", silo)
                        .isEqualTo(TENANT_HEAD));
    }

    /**
     * Discovery is the union of the catalogue and the registry, and neither source is allowed to hide a
     * schema from the other. A silo created by a promotion that died before flipping its placement row is
     * migrated anyway; a placement row naming a schema that is not there is reported rather than skipped,
     * because that tenant cannot be served and silence would be the wrong answer.
     */
    @Test
    void discoveryUnionsTheCatalogueAndTheRegistryAndReportsAPlacementWithNoSchema() {
        String unregistered = scratchSilo();
        String missing = TenantSchemas.siloSchema(UUID.randomUUID());
        UUID ghost = placementFor(missing, "ACTIVE");

        List<String> fleet = runner(realTenantScripts(), 2).discoverFleet();

        assertThat(fleet.getFirst())
                .describedAs("tenant_pool first — ADR 0010 §4.2's order, and the schema every unpromoted"
                        + " tenant depends on")
                .isEqualTo(TenantSchemas.TENANT_POOL);
        assertThat(fleet).contains(unregistered, missing);

        Manifest manifest = runner(realTenantScripts(), 2).fanOut(Mode.MIGRATE, List.of(missing, unregistered));

        assertThat(manifest.failures()).singleElement()
                .extracting(SchemaResult::schema).isEqualTo(missing);
        assertThat(only(manifest, missing).error()).contains("schema does not exist");
        assertThat(historyHead(unregistered))
                .describedAs("a silo with no placement row is still migrated — otherwise it sits behind"
                        + " forever with nothing to notice")
                .isEqualTo(TENANT_HEAD);
        assertThat(placementState(ghost)).isEqualTo("FAILED");
    }

    /**
     * <strong>The rescue is scoped to a FAILED this runner can tell it wrote, and under the shipped
     * {@code silo-per-org} default that scoping is the difference between a repair and a tenant that can
     * never be announced.</strong>
     *
     * <p>Two rows in one schema, so one pass decides both — which is also the shape production has, since
     * both statements are keyed by {@code schema_name}. The row with a version is a tenant that WAS
     * serving and whose schema fell behind: bringing it back is what the {@code case} exists for. The row
     * without one is {@code TenantPlacements.markFailed}'s — a signup whose schema was never built, and
     * therefore a tenant that was never announced. Flipping THAT to ACTIVE makes
     * {@code TenantPlacements.announce} decline forever ({@code where state <> 'ACTIVE'} matches
     * nothing), so the tenant keeps a registry row that reads healthy and never gets a trial, a billing
     * account or a search document.
     *
     * <p>The version assertion on the untouched row is not incidental: it is the marker that has to
     * survive, or the next failing pass stamps a version onto it and the pass after that rescues it.
     */
    @Test
    void aSuccessfulPassRescuesTheFailuresItWroteAndLeavesAProvisioningFailureAlone() {
        String silo = scratchSilo();
        UUID wasServing = placementFor(silo, "FAILED", "9001");
        UUID neverBuilt = placementFor(silo, "FAILED");

        Manifest manifest = runner(realTenantScripts(), 1).fanOut(Mode.MIGRATE, List.of(silo));

        assertThat(manifest.ok()).describedAs(manifest.report().toString()).isTrue();
        assertThat(placementState(wasServing))
                .describedAs("a schema that came back has to take its tenants with it, or nothing ever"
                        + " leaves the unhealthy-fleet query")
                .isEqualTo("ACTIVE");
        assertThat(placementVersion(wasServing)).isEqualTo(TENANT_HEAD);
        assertThat(placementState(neverBuilt))
                .describedAs("a migration pass may not announce a tenant nobody announced — ACTIVE here"
                        + " is what makes announce() decline for the rest of that tenant's life")
                .isEqualTo("FAILED");
        assertThat(placementVersion(neverBuilt))
                .describedAs("and the null is the marker itself: write a version here and the next"
                        + " failing pass makes this row indistinguishable from the one above")
                .isNull();
    }

    /** INFO answers the version-skew question ("is 100% of the fleet at N?") and writes nothing at all. */
    @Test
    void infoReportsWithoutTouchingTheSchemaOrTheRegistry() {
        UUID tenant = placementFor(TenantSchemas.TENANT_POOL, "FAILED");

        Manifest manifest = runner(realTenantScripts(), 1).fanOut(Mode.INFO, List.of(TenantSchemas.TENANT_POOL));

        assertThat(only(manifest).to()).isEqualTo(TENANT_HEAD);
        assertThat(only(manifest).pending()).isZero();
        assertThat(placementState(tenant))
                .describedAs("INFO is read-only — a mode that repaired the registry as a side effect would"
                        + " make the fleet look healthier than it is")
                .isEqualTo("FAILED");
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private static TenantMigrationRunner runner(MigrationScripts tenantScripts, int workers) {
        return new TenantMigrationRunner(
                pool,
                MigrationScripts.fromClasspath(TenantMigrationRunner.PLATFORM_LOCATION),
                tenantScripts,
                workers);
    }

    private static MigrationScripts realTenantScripts() {
        return MigrationScripts.fromClasspath(TenantMigrationRunner.TENANT_LOCATION, true);
    }

    /** The fixture sequence as of "release N": V9001 alone, so a schema has a version to be left at. */
    private static MigrationScripts releaseN() {
        return MigrationScripts.fromClasspath(FixturePaths.RELEASE_N, true);
    }

    /** "Release N+1": the same V9001 plus the V9002 that fails wherever fixture_wedge already exists. */
    private static MigrationScripts releaseNPlusOne() {
        return MigrationScripts.fromClasspath(FixturePaths.RELEASE_N_PLUS_ONE, true);
    }

    private String scratchSilo() {
        String schema = TenantSchemas.siloSchema(UUID.randomUUID());
        execute("create schema \"" + schema + "\"");
        scratchSchemas.add(schema);
        return schema;
    }

    /**
     * A placement row for an organization that does not exist. Legal by design — the registry holds a
     * soft ref, no FK, so that a placement can outlive the tenant whose bytes still have to be reclaimed
     * (V57's header). It is also what lets this test assert on the registry without creating an org.
     */
    private UUID placementFor(String schema, String state) {
        return placementFor(schema, state, null);
    }

    /**
     * The same row with a recorded version, which is the only thing that tells a FAILED this runner wrote
     * from a FAILED {@code TenantPlacements.markFailed} wrote — see
     * {@code TenantMigrationRunner.NOT_A_PROVISIONING_FAILURE}.
     */
    private UUID placementFor(String schema, String state, String version) {
        UUID orgId = UUID.randomUUID();
        try (Connection connection = pool.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        insert into platform.tenant_placement
                            (org_id, schema_name, datasource_name, state, schema_version, last_error, updated_at)
                        values (?, ?, 'primary', ?, ?, null, now())""")) {
            statement.setObject(1, orgId);
            statement.setString(2, schema);
            statement.setString(3, state);
            statement.setString(4, version);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not seed a placement row", failure);
        }
        scratchPlacements.add(orgId);
        return orgId;
    }

    /**
     * The row {@code DbMigrate} writes when a migration that was NOT in a transaction fails: same
     * version, {@code success = false}. Written by hand because the transactional default — which ADR
     * 0010 §4.2 makes mandatory for tenant migrations — cannot produce it, and repair mode exists for
     * the case where it reaches production through some other door.
     */
    private void wedgeHistory(String schema) {
        execute("insert into \"" + schema + "\".flyway_schema_history"
                + " (installed_rank, version, description, type, script, checksum, installed_by,"
                + "  installed_on, execution_time, success)"
                + " select coalesce(max(installed_rank), 0) + 1, '9002', 'fixture second', 'SQL',"
                + "        'V9002__fixture_second.sql', null, current_user, now(), 0, false"
                + "   from \"" + schema + "\".flyway_schema_history");
    }

    // ---------------------------------------------------------------------------------------------
    // Probes
    // ---------------------------------------------------------------------------------------------

    private static SchemaResult only(Manifest manifest) {
        assertThat(manifest.tenants()).hasSize(1);
        return manifest.tenants().getFirst();
    }

    private static SchemaResult only(Manifest manifest, String schema) {
        return manifest.tenants().stream()
                .filter(result -> result.schema().equals(schema))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(schema + " is not in " + manifest.report()));
    }

    private static String historyHead(String schema) {
        try (Connection connection = pool.getConnection()) {
            return TenantMigrationRunner.historyHead(connection, schema);
        } catch (SQLException failure) {
            throw new IllegalStateException("could not read the history head of " + schema, failure);
        }
    }

    private static int historyRows(String schema, String version) {
        return scalar("select count(*) from \"" + schema + "\".flyway_schema_history where version = ?",
                version, resultSet -> resultSet.getInt(1), 0);
    }

    private static boolean relationExists(String schema, String table) {
        return scalar("select to_regclass(?) is not null", schema + "." + table,
                resultSet -> resultSet.getBoolean(1), false);
    }

    private static String placementVersion(UUID orgId) {
        return placementColumn(orgId, "schema_version");
    }

    private static String placementState(UUID orgId) {
        return placementColumn(orgId, "state");
    }

    private static String placementError(UUID orgId) {
        return placementColumn(orgId, "last_error");
    }

    private static String placementColumn(UUID orgId, String column) {
        try (Connection connection = pool.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select " + column + " from platform.tenant_placement where org_id = ?")) {
            statement.setObject(1, orgId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not read " + column + " for " + orgId, failure);
        }
    }

    private interface Extractor<T> {
        T from(ResultSet resultSet) throws SQLException;
    }

    private static <T> T scalar(String sql, String parameter, Extractor<T> extractor, T absent) {
        try (Connection connection = pool.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? extractor.from(rows) : absent;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("probe failed: " + sql, failure);
        }
    }

    private static void execute(String sql) {
        try (Connection connection = pool.getConnection()) {
            execute(connection, sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("could not run: " + sql, failure);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * The highest version in {@code db/migration/tenant}, read off the filenames. Written down nowhere:
     * a constant here would be a second copy of the migration counter and the one that goes stale the
     * next time a tenant migration lands.
     */
    private static String headOfTenantSequence() {
        try (var files = Files.list(Path.of("src", "main", "resources", TenantMigrationRunner.TENANT_LOCATION))) {
            return files.map(path -> path.getFileName().toString())
                    .map(VERSION::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .max(Comparator.comparingInt(Integer::parseInt))
                    .orElseThrow(() -> new IllegalStateException("no tenant migrations found"));
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read the tenant migration directory", failure);
        }
    }
}
