package ug.co.smsone.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ug.co.smsone.shared.persistence.MigrationScripts;
import ug.co.smsone.shared.persistence.TenantDataSources;
import ug.co.smsone.shared.persistence.TenantMigrationRunner;
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;

/**
 * <strong>A second, REAL database for the suite: one tenant served from another Postgres entirely,
 * everybody else from the primary</strong> (ADR 0011; the {@code TenantRemotes} sibling to
 * {@link TenantSilos} that its §8 item 7 predicts).
 *
 * <h2>Why the suite needs a second container at all</h2>
 *
 * <p>Every routing claim ADR 0011 makes — the pool picked by {@code tenant_placement.datasource_name},
 * the platform axis staying on primary, the two-connection sweep, the minimal remote {@code platform}
 * schema tripwire — is unprovable against one database, exactly as Phase 5's fan-out claims were
 * unprovable against one schema. A "remote" simulated as another schema in the same container would
 * pass with routing entirely absent, because every axis would land on the same server anyway. ADR 0003
 * applies: real Postgres, or the mechanism is not being tested.
 *
 * <h2>The container: lazy, then shared for the JVM</h2>
 *
 * <p>Started on first use rather than in a static block, because {@link AbstractIntegrationTest} is the
 * base of the whole suite and only a handful of classes need a second database — eagerly starting one
 * for every test would tax the constrained Docker VMs the suite already crashes when image pulls storm.
 * Same image and the same {@code wal_level=logical} flags as the primary: a cutover test cannot run
 * replication against a container that cannot host a subscription. Database name {@code remotedb},
 * deliberately different from the primary's, so {@code select current_database()} is a one-line proof
 * of which server answered — the assertion no single-container fixture can make.
 *
 * <h2>What "prepared" means, and what is deliberately missing</h2>
 *
 * <p>The remote is given {@code ext} (with {@code pg_trgm}), {@code no_tenant}, and a {@code platform}
 * schema holding ONLY {@code event_publication} — ADR 0011 §5.1's minimal shape, byte-for-byte V2's
 * DDL. Nothing else, on purpose: the absence IS the tripwire, and {@code TenantRemoteRoutingTest}
 * asserts a stray platform-qualified statement dies rather than landing in a copy nobody reads. Do not
 * "fix" a test failure by widening this schema.
 *
 * <h2>Placing a tenant here is the test-side stand-in for a CUTOVER, and copies nothing</h2>
 *
 * <p>{@link #placeOnRemote} builds the silo on the remote (real Flyway, the real tenant sequence),
 * then flips the primary registry row to name {@link #DATASOURCE} — the same one-row flip ADR 0011
 * §7.2 step 7 describes, minus the replication that moves existing rows. Like {@link TenantSilos#place}
 * it therefore refuses a tenant that is already serving or already has pooled rows: those need the real
 * cutover machinery, and a fixture that silently stranded rows would fail three files away as "the row
 * I just wrote is not there".
 *
 * <h2>Cleanup discipline</h2>
 *
 * <p>Registered as an instance extension on the classes that call {@link #placeOnRemote}: after every
 * test it drops every silo-shaped schema ON THE REMOTE and forgets every memoized route. The primary
 * side needs nothing new — {@link TenantSilos} is already registered on {@code AbstractIntegrationTest}
 * and its placement sweep matches rows by SCHEMA shape, which covers rows naming this datasource too.
 */
public final class TenantRemotes implements BeforeEachCallback, AfterEachCallback {

    /** The name the fixture registers and placement rows select. Matches ADR 0011 §4.1's pattern. */
    public static final String DATASOURCE = "remote-a";

    private static final String SILO_SCHEMAS_IN_CATALOGUE =
            "select schema_name from information_schema.schemata"
                    + " where schema_name ~ '^t_[0-9a-f]{32}$'";

    @SuppressWarnings("resource") // the AbstractIntegrationTest rule: Testcontainers owns it, Ryuk reaps it
    private static PostgreSQLContainer remote;

    private ApplicationContext context;

    /** The second container, started and prepared on first use. */
    public static synchronized PostgreSQLContainer container() {
        if (remote == null) {
            @SuppressWarnings("resource")
            PostgreSQLContainer started = new PostgreSQLContainer("postgres:18.4-alpine")
                    .withDatabaseName("remotedb")
                    // Small on purpose — one remote tenant per test, not a fleet — but comfortably
                    // above the app-side pool so a saturated test cannot wedge on the server side.
                    .withCommand("postgres", "-c", "max_connections=50",
                            // The primary's own flags (AbstractIntegrationTest): a cutover test reuses
                            // this container as a replication TARGET and needs a publication of its own
                            // for the reverse stream, which wal_level=replica cannot host.
                            "-c", "wal_level=logical",
                            "-c", "max_replication_slots=20",
                            "-c", "max_wal_senders=20");
            started.start();
            prepare(started);
            remote = started;
        }
        return remote;
    }

    /**
     * Registers {@code app.tenancy.datasources.remote-a.*} for a test class's context. Call from a
     * {@code @DynamicPropertySource} method; the supplier starts the container on first resolution, so
     * classes that never build a context with this pay nothing.
     */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("app.tenancy.datasources." + DATASOURCE + ".url", () -> container().getJdbcUrl());
        registry.add("app.tenancy.datasources." + DATASOURCE + ".username", () -> container().getUsername());
        registry.add("app.tenancy.datasources." + DATASOURCE + ".password", () -> container().getPassword());
        // Small for the same reason the primary test pool is (application-test.yaml): many cached
        // contexts share the containers, and 3 covers a request thread + an async listener.
        registry.add("app.tenancy.datasources." + DATASOURCE + ".pool.maximum-pool-size", () -> "3");
    }

    /**
     * Gives {@code orgId} a silo ON THE REMOTE: schema created and migrated there through the real
     * tenant sequence, and the primary registry flipped to {@code (t_<hex>, remote-a)}, ACTIVE, at the
     * version the migration reported. Every other organization stays wherever it was.
     *
     * @return the silo's schema name, which is also {@code TenantSchemas.siloSchema(orgId)}
     */
    public String placeOnRemote(UUID orgId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TenantRemotes.placeOnRemote must run OUTSIDE a transaction"
                    + " — it runs Flyway against another database and flips a registry row that must"
                    + " not roll back with yours (the TenantSilos.place rule, one database over).");
        }
        ApplicationContext beans = requireContext();
        TenantPlacements placements = beans.getBean(TenantPlacements.class);
        String silo = TenantSchemas.siloSchema(orgId);
        if (!placements.reserve(orgId, silo)) {
            throw new IllegalStateException("organization " + orgId + " is already serving — moving a"
                    + " serving tenant to another database is a CUTOVER (ADR 0011 §7.2), and this"
                    + " fixture places a tenant before its rows exist and copies nothing.");
        }
        // The real tenant sequence over the REAL configured pool — the same pool the router will
        // borrow from, so a fixture-built silo and a routed statement cannot disagree about which
        // database "remote-a" is.
        DataSource pool = beans.getBean(TenantDataSources.class).poolFor(DATASOURCE);
        MigrationScripts scripts =
                MigrationScripts.fromClasspath(TenantMigrationRunner.TENANT_LOCATION, true);
        MigrateResult result = Flyway.configure()
                .dataSource(pool)
                .resourceProvider(scripts)
                .javaMigrationClassProvider(scripts)
                .schemas(silo)
                .defaultSchema(silo)
                .createSchemas(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load()
                .migrate();
        String version = result.targetSchemaVersion;
        // The flip, as one statement — the fixture-sized stand-in for ADR 0011 §7.2 step 7. Direct SQL
        // rather than a TenantPlacements method, deliberately: the shipped write surface for moving a
        // serving tenant belongs to the cutover machinery, and a fixture must not widen it.
        beans.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update("""
                update platform.tenant_placement
                   set datasource_name = ?, state = 'ACTIVE', schema_version = ?, last_error = null,
                       updated_at = now()
                 where org_id = ?
                """, DATASOURCE, version, orgId);
        // The route memo is what actually sends the next statement to the remote — same reason
        // TenantSilos.place drops it: production covers this window with the cutover freeze, a test
        // has no freeze and the very next line expects the remote.
        TenantRoutes.forget(orgId);
        return silo;
    }

    /** A direct line to the remote, for the schema-qualified physical assertions routing must not touch. */
    public static Connection remoteConnection() throws SQLException {
        PostgreSQLContainer container = container();
        return DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        // Total and idempotent, the TenantSilos discipline: a leaked remote silo fails later classes'
        // fan-outs with a message naming neither this fixture nor the test that leaked.
        TenantRoutes.forgetEverything();
        try (Connection connection = remoteConnection()) {
            for (String silo : siloSchemas(connection)) {
                try (Statement statement = connection.createStatement()) {
                    // One schema per transaction (autocommit), the ADR 0010 §5.12 lock arithmetic.
                    statement.execute(
                            "drop schema \"" + TenantSchemas.requireSiloSchema(silo) + "\" cascade");
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not sweep the remote tenant silos", failure);
        }
        // Placement rows on the PRIMARY naming these silos are swept by TenantSilos, which is
        // registered on AbstractIntegrationTest and matches rows by schema shape.
    }

    private static List<String> siloSchemas(Connection connection) throws SQLException {
        var schemas = new ArrayList<String>();
        try (PreparedStatement statement = connection.prepareStatement(SILO_SCHEMAS_IN_CATALOGUE);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                schemas.add(rows.getString(1));
            }
        }
        return schemas;
    }

    /**
     * ADR 0011 §5.1's minimal remote shape, once per container: {@code ext} + {@code pg_trgm} (tenant
     * DDL builds trigram indexes and would otherwise fail mid-sequence), {@code no_tenant}, and a
     * {@code platform} schema holding ONLY {@code event_publication} — V2's DDL verbatim, fresh and
     * empty, because the outbox follows the transaction (§5) and everything else's absence is the
     * tripwire.
     */
    private static void prepare(PostgreSQLContainer container) {
        try (Connection connection = DriverManager.getConnection(
                        container.getJdbcUrl(), container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create schema if not exists " + TenantSchemas.EXTENSIONS);
            statement.execute("create extension if not exists pg_trgm schema " + TenantSchemas.EXTENSIONS);
            statement.execute("create schema if not exists " + TenantSchemas.NO_TENANT);
            statement.execute("create schema if not exists " + TenantSchemas.PLATFORM);
            statement.execute("""
                    create table if not exists platform.event_publication (
                      id                     uuid not null,
                      listener_id            text not null,
                      event_type             text not null,
                      serialized_event       text not null,
                      publication_date       timestamp with time zone not null,
                      completion_date        timestamp with time zone,
                      status                 text,
                      completion_attempts    int,
                      last_resubmission_date timestamp with time zone,
                      primary key (id)
                    )""");
        } catch (SQLException failure) {
            throw new IllegalStateException("could not prepare the remote tenant database", failure);
        }
    }

    /** Captured here rather than injected — the {@link TenantSilos#beforeEach} reasoning verbatim. */
    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        this.context = SpringExtension.getApplicationContext(extensionContext);
    }

    private ApplicationContext requireContext() {
        if (context == null) {
            throw new IllegalStateException("TenantRemotes needs the Spring test context — register it"
                    + " as a non-static field: @RegisterExtension final TenantRemotes remotes = new"
                    + " TenantRemotes(); and call placeOnRemote from inside a test method.");
        }
        return context;
    }
}
