package ug.co.smsone.compliance.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import ug.co.smsone.shared.persistence.TenantMigrationRunner;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Manifest;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Mode;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * <strong>The far side of an extraction, stood up for real:</strong> a brand-new database in the suite's
 * container, built from nothing by the same two migration sequences production runs, then handed the
 * bundle's script and nothing else.
 *
 * <h2>Why a separate database and not a separate schema</h2>
 *
 * <p>The script addresses {@code platform.<table>} by name and the tenant's tables through
 * {@code search_path} — which is the whole point of ADR 0010 §2's two forms of address — so it can only
 * be replayed somewhere those names are free. Restoring into the suite's own database would collide with
 * every row already there on the first {@code platform.plan} insert, and a restore that has to dodge the
 * source's rows is not the restore an operator performs.
 *
 * <p>The migrations are run by {@link TenantMigrationRunner} rather than by a dump of the source schema,
 * and that is the bundle's own claim being tested rather than a convenience: the extracted deployment is
 * the same jar, so its schema comes from {@code db/migration/}, and a dumped one would arrive carrying
 * the SOURCE's {@code flyway_schema_history} — another schema's applied-version record wearing this
 * one's name.
 *
 * <h2>The script goes in as one string, on purpose</h2>
 *
 * <p>{@code Statement.execute} on the whole file is the closest thing JDBC has to {@code psql -f}, and
 * the script's own {@code begin} / {@code commit} then do what the header promises: a failure anywhere
 * leaves the database empty rather than half a tenant. Splitting it into statements here would test a
 * different artifact from the one the runbook hands to {@code psql}.
 */
final class RestoredDatabase implements AutoCloseable {

    /**
     * Four workers would be pointless here — the fresh database has exactly one tenant home — and two is
     * the runner's own minimum useful shape (a platform pass, then a fan-out of one).
     */
    private static final int WORKERS = 2;

    private final String database;
    private final DataSource dataSource;

    private RestoredDatabase(String database, DataSource dataSource) {
        this.database = database;
        this.dataSource = dataSource;
    }

    /**
     * Creates the database, migrates it, and replays {@code script} into it.
     *
     * @throws IllegalStateException if the migration sequences cannot build the database, which is a
     *     failure of the migrations rather than of the bundle and should say so
     */
    static RestoredDatabase build(String script) throws SQLException {
        String database = "restored_" + UUID.randomUUID().toString().replace("-", "");
        execute(adminDataSource(AbstractIntegrationTest.POSTGRES.getDatabaseName()),
                "create database " + database);

        DataSource dataSource = adminDataSource(database);
        Manifest migrated = TenantMigrationRunner.fromClasspath(dataSource, WORKERS).run(Mode.MIGRATE);
        if (!migrated.ok()) {
            throw new IllegalStateException("the migration sequences could not build the restore target,"
                    + " so nothing restored into it would be testing the bundle: " + migrated.report());
        }
        execute(dataSource, script);
        return new RestoredDatabase(database, dataSource);
    }

    JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Drops the database. {@code with (force)} because Flyway's and the restore's connections are closed
     * but the server may not have reaped them yet, and a leaked template database would fail every later
     * run of this test with a name collision it could not explain.
     */
    void drop() throws SQLException {
        execute(adminDataSource(AbstractIntegrationTest.POSTGRES.getDatabaseName()),
                "drop database if exists " + database + " with (force)");
    }

    @Override
    public void close() throws SQLException {
        drop();
    }

    /**
     * Unpooled, like {@code TenantSchemaBootstrap}'s: the runner pins the baseline {@code search_path} on
     * every borrow, so a fresh physical connection per borrow is correct, and a pool here would be a
     * second lifecycle for a test to shut down.
     */
    private static DataSource adminDataSource(String database) {
        return new DriverManagerDataSource(
                "jdbc:postgresql://" + AbstractIntegrationTest.POSTGRES.getHost() + ":"
                        + AbstractIntegrationTest.POSTGRES.getFirstMappedPort() + "/" + database,
                AbstractIntegrationTest.POSTGRES.getUsername(),
                AbstractIntegrationTest.POSTGRES.getPassword());
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
