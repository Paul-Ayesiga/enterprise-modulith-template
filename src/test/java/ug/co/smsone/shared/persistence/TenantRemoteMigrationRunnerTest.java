package ug.co.smsone.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Manifest;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Mode;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.SchemaResult;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.TenantRemotes;

/**
 * <b>ADR 0011 §4.3's migration half, against two REAL databases: remote-placed silos are discovered,
 * migrated over their own pool, and recorded — like everything else — in the PRIMARY registry.</b>
 *
 * <p>No Spring context, on purpose — {@code TenantMigrationRunnerTest}'s reasoning verbatim: the
 * subject runs in a Kubernetes Job with none of the application around it. It borrows the suite's
 * primary container and {@link TenantRemotes}' second one, and every readback is a direct connection
 * to the database it is about.
 */
class TenantRemoteMigrationRunnerTest {

    private static HikariDataSource primary;
    private static HikariDataSource remotePool;

    private final List<UUID> scratchPlacements = new ArrayList<>();

    @BeforeAll
    static void openPools() {
        primary = pool("remote-runner-primary",
                AbstractIntegrationTest.POSTGRES.getJdbcUrl(),
                AbstractIntegrationTest.POSTGRES.getUsername(),
                AbstractIntegrationTest.POSTGRES.getPassword(),
                TenantMigrationRunner.connectionsRequired(2));
        remotePool = pool("remote-runner-remote",
                TenantRemotes.container().getJdbcUrl(),
                TenantRemotes.container().getUsername(),
                TenantRemotes.container().getPassword(),
                // Deliberately SMALLER than connectionsRequired(2): workersAffordedBy must cap the
                // remote fan-out to what this pool can lend, or the run deadlocks — which is the
                // regression this number exists to catch.
                4);
    }

    @AfterAll
    static void closePools() {
        if (primary != null) {
            primary.close();
        }
        if (remotePool != null) {
            remotePool.close();
        }
    }

    /** The TenantMigrationRunnerTest discipline: nothing this class makes outlives the test. */
    @AfterEach
    void dropScratch() throws SQLException {
        try (Connection remote = TenantRemotes.remoteConnection();
                Statement statement = remote.createStatement();
                ResultSet rows = statement.executeQuery("select schema_name from"
                        + " information_schema.schemata where schema_name ~ '^t_[0-9a-f]{32}$'")) {
            var silos = new ArrayList<String>();
            while (rows.next()) {
                silos.add(rows.getString(1));
            }
            for (String silo : silos) {
                try (Statement drop = remote.createStatement()) {
                    // One schema per transaction, always (ADR 0010 §5.12's lock arithmetic).
                    drop.execute("drop schema \"" + TenantSchemas.requireSiloSchema(silo) + "\" cascade");
                }
            }
        }
        try (Connection connection = primary.getConnection()) {
            for (UUID orgId : scratchPlacements) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "delete from platform.tenant_placement where org_id = ?")) {
                    statement.setObject(1, orgId);
                    statement.executeUpdate();
                }
            }
        }
        scratchPlacements.clear();
    }

    /**
     * THE GATE: a registry row naming {@code (t_<hex>, remote-a)} whose schema exists on the remote is
     * found by the per-datasource pass, migrated to head OVER THE REMOTE POOL, and its version recorded
     * in the primary registry — the same never-abort, same recording, one database over. The remote's
     * own {@code flyway_schema_history} (inside the silo, where per-schema history lives) is the
     * physical proof the Flyway really ran there.
     */
    @Test
    void aRemotePlacedSiloIsDiscoveredMigratedOnItsOwnPoolAndRecordedOnPrimary() throws SQLException {
        UUID orgId = UUID.randomUUID();
        String silo = TenantSchemas.siloSchema(orgId);
        scratchPlacements.add(orgId);
        try (Connection remote = TenantRemotes.remoteConnection();
                Statement statement = remote.createStatement()) {
            // A bare schema, the way a half-built destination arrives: the runner migrates, never creates.
            statement.execute("create schema \"" + silo + "\"");
        }
        placement(orgId, silo, TenantRemotes.DATASOURCE, "ACTIVE", null);

        Manifest manifest = runner().run(Mode.MIGRATE);

        assertThat(manifest.ok()).describedAs(manifest.report().toString()).isTrue();
        SchemaResult result = only(manifest, silo);
        assertThat(result.datasource()).isEqualTo(TenantRemotes.DATASOURCE);
        assertThat(result.applied()).isPositive();
        String remoteHead;
        try (Connection remote = TenantRemotes.remoteConnection()) {
            remoteHead = TenantMigrationRunner.historyHead(remote, silo);
        }
        assertThat(remoteHead).as("the sequence physically landed on the remote").isNotNull();
        assertThat(result.to()).isEqualTo(remoteHead);
        assertThat(recordedVersion(orgId))
                .as("and the PRIMARY registry — the only registry — records the remote schema's version")
                .isEqualTo(remoteHead);
    }

    /**
     * The two failure shapes stay per tenant and the registry tells the truth in both directions. A
     * registry row whose schema does not exist on its named datasource is reported and marked FAILED —
     * the pre-existing semantics, one database over. A datasource that is UNREACHABLE fails its tenants
     * in the manifest and leaves the registry ALONE: those schemas' state is unknown, not proven unfit,
     * and a FAILED row would stand a serving tenant's fan-outs down over what may be the migration
     * job's own network path. Both in one run, which also proves never-abort across the group boundary.
     */
    @Test
    void aMissingRemoteSchemaIsMarkedFailedWhileAnUnreachableDatasourceLeavesTheRegistryAlone()
            throws SQLException {
        UUID missing = UUID.randomUUID();
        String missingSilo = TenantSchemas.siloSchema(missing);
        scratchPlacements.add(missing);
        placement(missing, missingSilo, TenantRemotes.DATASOURCE, "ACTIVE", "53");

        UUID marooned = UUID.randomUUID();
        String maroonedSilo = TenantSchemas.siloSchema(marooned);
        scratchPlacements.add(marooned);
        placement(marooned, maroonedSilo, "dead-end", "ACTIVE", "53");

        Map<String, javax.sql.DataSource> remotes = new LinkedHashMap<>();
        remotes.put(TenantRemotes.DATASOURCE, remotePool);
        try (HikariDataSource deadEnd = unreachablePool()) {
            remotes.put("dead-end", deadEnd);
            Manifest manifest = TenantMigrationRunner.fromClasspath(primary, remotes, 2).run(Mode.MIGRATE);

            assertThat(manifest.ok()).isFalse();
            assertThat(only(manifest, missingSilo).error())
                    .contains("schema does not exist");
            assertThat(only(manifest, maroonedSilo).error())
                    .contains("unreachable");
            assertThat(manifest.exitCode())
                    .as("some tenants are behind, nothing fatal: the runbook's re-run code")
                    .isEqualTo(1);
        }

        assertThat(stateOf(missing))
                .as("a schema its own datasource disowns is a broken tenant, recorded")
                .isEqualTo("FAILED");
        assertThat(stateOf(marooned))
                .as("an unreachable datasource proves nothing about the schema — the registry is left"
                        + " saying what was last known true")
                .isEqualTo("ACTIVE");
        assertThat(recordedVersion(marooned)).isEqualTo("53");
    }

    private static TenantMigrationRunner runner() {
        return TenantMigrationRunner.fromClasspath(
                primary, Map.of(TenantRemotes.DATASOURCE, remotePool), 2);
    }

    private void placement(UUID orgId, String schema, String datasource, String state, String version) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        insert into platform.tenant_placement
                               (org_id, schema_name, datasource_name, state, schema_version, updated_at)
                        values (?, ?, ?, ?, ?, now())
                        """)) {
            statement.setObject(1, orgId);
            statement.setString(2, schema);
            statement.setString(3, datasource);
            statement.setString(4, state);
            statement.setString(5, version);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not seed a placement row", failure);
        }
    }

    private String recordedVersion(UUID orgId) throws SQLException {
        return readColumn(orgId, "schema_version");
    }

    private String stateOf(UUID orgId) throws SQLException {
        return readColumn(orgId, "state");
    }

    private String readColumn(UUID orgId, String column) throws SQLException {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select " + column + " from platform.tenant_placement where org_id = ?")) {
            statement.setObject(1, orgId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static SchemaResult only(Manifest manifest, String schema) {
        return manifest.tenants().stream()
                .filter(result -> schema.equals(result.schema()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(schema + " is not in " + manifest.report()));
    }

    /**
     * A pool nothing listens behind: port 1 is reserved and closed on every sane box. The Hikari shape
     * mirrors {@code TenantDataSources.build} — no initialization check, short connection timeout —
     * because that is exactly the pool an unreachable configured remote hands the runner in production.
     */
    private static HikariDataSource unreachablePool() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("dead-end");
        config.setJdbcUrl("jdbc:postgresql://localhost:1/nowhere");
        config.setUsername("nobody");
        config.setPassword("nothing");
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(300);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private static HikariDataSource pool(String name, String url, String user, String password, int max) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(max);
        return new HikariDataSource(config);
    }
}
