package ug.co.smsone.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ug.co.smsone.shared.persistence.UnknownTenantDataSourceException;
import ug.co.smsone.shared.queue.QueueSignals;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantRemotes;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>ADR 0011's routing seam, against two REAL databases: the pool is picked by
 * {@code tenant_placement.datasource_name}, the platform axis never leaves primary, and a tenant this
 * deployment cannot route fails alone.</b>
 *
 * <p>The discipline is {@code TenantRoutesTest}'s, one database over: every load-bearing assertion is
 * made over a DIRECT connection to the container it is about, never through the router — a test that
 * seeds and reads through {@code TenantContext} asks the router the same question twice and passes
 * with routing absent. {@code select current_database()} is the one routed read used deliberately,
 * because its answer is exactly the fact under test and the two containers were given different
 * database names so it cannot lie.
 */
class TenantRemoteRoutingTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @RegisterExtension
    final TenantRemotes remotes = new TenantRemotes();

    @DynamicPropertySource
    static void remoteDatasource(DynamicPropertyRegistry registry) {
        TenantRemotes.register(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantFanOut fanOut;

    @Autowired
    private TenantSchemaFloor floor;

    @Autowired
    private QueueSignals signals;

    /**
     * The headline claim: one tenant served from a second Postgres, byte-for-byte the same code path.
     * The write goes through the routed DataSource on the tenant's own axis; the readbacks are direct
     * connections to each container, so a misroute in either direction has nowhere to hide — the row
     * must be ON the remote, and NOWHERE on primary, whose catalogue does not even hold the silo.
     */
    @Test
    void aTenantPlacedOnAnotherDatabaseIsServedFromItAndOnlyIt() throws SQLException {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "rem-" + UUID.randomUUID());
        String silo = remotes.placeOnRemote(orgId);

        assertThat(TenantRoutes.routeOf(orgId))
                .as("the route is the (schema, datasource) pair, resolved from the one registry row")
                .isEqualTo(new TenantRoutes.Route(silo, TenantRemotes.DATASOURCE));

        UUID entry = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update(
                "insert into audit_log (id, org_id, action, target, occurred_at, version, created_at)"
                        + " values (?, ?, 'REMOTE_ROUTE_PROBE', 'probe', now(), 0, now())",
                entry, orgId));

        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(countIn(remote, silo, entry))
                    .as("the tenant's row is physically in the remote database")
                    .isEqualTo(1);
        }
        Boolean siloOnPrimary = jdbc.queryForObject(
                "select to_regclass(?) is not null", Boolean.class, silo + ".audit_log");
        assertThat(siloOnPrimary)
                .as("primary does not even hold the schema — the row cannot have gone anywhere near it")
                .isFalse();
        Integer inPool = jdbc.queryForObject(
                "select count(*) from tenant_pool.audit_log where id = ?", Integer.class, entry);
        assertThat(inPool).as("and the pool holds nothing about it").isZero();

        // The two axes answer from two different SERVERS — the assertion no one-container test can make.
        assertThat(TenantContext.callAs(orgId,
                () -> jdbc.queryForObject("select current_database()", String.class)))
                .isEqualTo("remotedb");
        assertThat(TenantContext.callAsPlatform(
                () -> jdbc.queryForObject("select current_database()", String.class)))
                .isEqualTo(POSTGRES.getDatabaseName());
    }

    /**
     * ADR 0011 §5.1's tripwire, asserted rather than assumed: the remote's {@code platform} schema
     * holds only {@code event_publication}, so a stray platform-qualified statement on the tenant's
     * axis dies with "relation does not exist" instead of landing in a copy nobody reads. This is the
     * failure mode every unconverted platform-write call site is supposed to produce, and if this test
     * ever starts failing because the fixture grew the table, the fixture is what regressed.
     */
    @Test
    void aStrayPlatformQualifiedStatementOnTheRemoteAxisDiesLoudly() {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "trip-" + UUID.randomUUID());
        remotes.placeOnRemote(orgId);

        assertThatThrownBy(() -> TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from platform.tenant_placement", Integer.class)))
                .isInstanceOf(BadSqlGrammarException.class)
                .hasMessageContaining("tenant_placement");

        // And the sanctioned spelling of the same read works: an explicit platform axis is a borrow
        // from PRIMARY, where the registry lives — the two-connection choreography's first half.
        Integer placements = TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select count(*) from platform.tenant_placement where org_id = ?", Integer.class, orgId));
        assertThat(placements).isEqualTo(1);
    }

    /**
     * <b>The two-connection sweep, end to end through the real classes</b>: {@code TenantFanOut} hands
     * out the remote home (grouped after primary's), {@code TenantHomeSweep} pins its axis, and inside
     * the visit the signal is read from PRIMARY under {@code callAsPlatform} while the row work lands
     * on the REMOTE — two borrows, two databases, two transactions (the trap {@code TenantHomeSweep}'s
     * javadoc documents, exercised in its safe form).
     */
    @Test
    void aSweepOverARemoteHomeReadsSignalsFromPrimaryAndWritesRowsToTheRemote() throws SQLException {
        UUID pooledOrg = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "pl-" + UUID.randomUUID());
        UUID primarySiloOrg = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "ps-" + UUID.randomUUID());
        UUID remoteOrg = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "rs-" + UUID.randomUUID());
        String primarySilo = silos.place(primarySiloOrg);
        String remoteSilo = remotes.placeOnRemote(remoteOrg);
        signals.raise("webhook", remoteOrg);

        TenantFanOut.Fleet fleet = fanOut.fleet();
        List<TenantHome> homes = fleet.homes();
        assertThat(homes.get(0).pooled()).as("the pool is first, always").isTrue();
        assertThat(homes).extracting(TenantHome::schema)
                .as("grouped by datasource: primary's silos before the remote's")
                .containsSubsequence(primarySilo, remoteSilo);
        assertThat(fleet.homeOf(remoteOrg))
                .hasValueSatisfying(home -> {
                    assertThat(home.schema()).isEqualTo(remoteSilo);
                    assertThat(home.datasource()).isEqualTo(TenantRemotes.DATASOURCE);
                    assertThat(home.onPrimary()).isFalse();
                });

        UUID entry = UUID.randomUUID();
        AtomicLong signalsSeenFromRemoteVisit = new AtomicLong(-1);
        TenantHomeSweep.Swept swept = new TenantHomeSweep("remote-routing-test").over(
                homes, Clock.systemUTC(), Instant.now().plusSeconds(60), (home, deadline) -> {
                    if (!remoteSilo.equals(home.schema())) {
                        return;
                    }
                    // Half one: the coordination read, explicitly on the platform axis — its own
                    // borrow, from PRIMARY, because the signal table exists nowhere else (ADR 0011 §5).
                    Long due = TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                            "select count(*) from platform.queue_signal where queue = 'webhook'"
                                    + " and org_id = ?", Long.class, home.axis()));
                    signalsSeenFromRemoteVisit.set(due == null ? -1 : due);
                    // Half two: the row work, on the axis the sweep already pinned — the REMOTE.
                    jdbc.update("insert into audit_log (id, org_id, action, target, occurred_at,"
                                    + " version, created_at)"
                                    + " values (?, ?, 'REMOTE_SWEEP_PROBE', 'probe', now(), 0, now())",
                            entry, home.axis());
                });

        swept.rethrowFirstFailure();
        assertThat(swept.visited()).isEqualTo(homes.size());
        assertThat(signalsSeenFromRemoteVisit.get())
                .as("the visit read its tenant's signal out of the primary's table")
                .isEqualTo(1);
        try (Connection remote = TenantRemotes.remoteConnection()) {
            assertThat(countIn(remote, remoteSilo, entry))
                    .as("and its row work landed in the remote database")
                    .isEqualTo(1);
        }
        assertThat(fleet.homeOf(pooledOrg)).hasValueSatisfying(home ->
                assertThat(home.pooled()).as("everyone else is exactly where they were").isTrue());
    }

    /**
     * <b>ADR 0011 §4.2: a placement naming a datasource nobody configured fails THAT tenant loudly and
     * leaves the fleet serving.</b> Three layers, one broken row: the edge refuses per tenant through
     * the floor (503's decision), the fan-out withholds the home rather than throwing it into every
     * sweep ({@code aHomeNobodyBuilt}'s doctrine), and the borrow backstop throws the typed exception
     * rather than guessing a pool — ADR 0010 §1's misroute being the one outcome none of the three may
     * produce.
     */
    @Test
    void aPlacementNamingAnUnconfiguredDatasourceFailsThatTenantAndOnlyThatTenant() {
        UUID marooned = UUID.randomUUID();
        UUID healthy = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "ok-" + UUID.randomUUID());
        String maroonedSilo = TenantSchemas.siloSchema(marooned);
        jdbc.update("""
                insert into platform.tenant_placement
                       (org_id, schema_name, datasource_name, state, schema_version, updated_at)
                values (?, ?, 'nobody-configured-this', 'ACTIVE', ?, now())
                """, marooned, maroonedSilo, String.valueOf(TenantSchemaFloor.MIN_TENANT_SCHEMA_VERSION));
        TenantRoutes.forget(marooned);
        try {
            assertThat(floor.admits(marooned)).as("the edge refuses this tenant").isFalse();
            assertThat(floor.admits(healthy)).as("and nobody else").isTrue();

            assertThatThrownBy(() -> TenantContext.callAs(marooned,
                    () -> jdbc.queryForObject("select 1", Integer.class)))
                    .as("the borrow backstop: typed, loud, and never a guessed pool. Spring's"
                            + " DataSourceUtils wraps an IllegalStateException from getConnection in"
                            + " CannotGetJdbcConnectionException, so the typed refusal is the ROOT of"
                            + " the chain — still naming the row's datasource and the config key.")
                    .hasRootCauseInstanceOf(UnknownTenantDataSourceException.class)
                    .hasStackTraceContaining("nobody-configured-this");

            TenantFanOut.Fleet fleet = fanOut.fleet();
            assertThat(fleet.homeOf(marooned))
                    .as("no sweep gets handed a home this deployment cannot reach")
                    .isEmpty();
            assertThat(fleet.homes()).extracting(TenantHome::schema).doesNotContain(maroonedSilo);
            assertThat(fleet.homeOf(healthy)).isPresent();
        } finally {
            jdbc.update("delete from platform.tenant_placement where org_id = ?", marooned);
            TenantRoutes.forget(marooned);
        }
    }

    private static int countIn(Connection connection, String silo, UUID entry) throws SQLException {
        // Qualified and direct: the question is about a specific schema in a specific database, and
        // routing through search_path would ask it of whatever the router already believes.
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from " + TenantSchemas.requireSiloSchema(silo) + ".audit_log where id = ?")) {
            statement.setObject(1, entry);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }
}
