package ug.co.smsone.shared.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * Routes every connection borrow on two axes — WHICH DATABASE, then which schema in it — from
 * {@link TenantContext#current()} (ADR 0010 §3.1, ADR 0011 §4.3). This is the seam the whole tenancy
 * design hangs off: it is in front of Hibernate <em>and</em> in front of the 22 classes that hold a
 * {@code JdbcTemplate} or a {@code DataSource} directly, plus ShedLock and the Modulith event
 * registry — which is exactly why Hibernate's own {@code multi_tenant.schema_mapper} was rejected.
 * It covers Hibernate only, and treats a null tenant as "no multi-tenancy" rather than as an error.
 *
 * <p><strong>The order is resolve route → pick pool → borrow → set {@code search_path}, and every arrow
 * is load-bearing.</strong> Resolving first keeps the placement read (which borrows its own primary
 * connection inside {@link TenantRoutes}) sequential with the borrow it informs, instead of nested
 * under it — with a small pool the other nesting is a deadlock, every thread holding one connection and
 * waiting for a connection only another waiting thread can release. Picking the pool before borrowing
 * is the same rule one hop out: {@code poolFor} can throw (an unconfigured datasource name), and it
 * must do so before a connection is held. Proven against two real containers with both pools at
 * maximum-pool-size 1 under eight concurrent borrowers before this shipped.
 *
 * <p><strong>Which pool each axis borrows from is not symmetric, and the asymmetry is ADR 0011 §5.</strong>
 * The PLATFORM axis and the poison ABSENT state always borrow from PRIMARY — the person graph, the
 * catalogs, the registry, the queue signals, the locks and the freezes live nowhere else, so a
 * platform-qualified statement issued under {@code runAsPlatform} reaches the right database however
 * many others exist. Only a tenant axis can route away, and only to the database its own placement row
 * names. A remote database's {@code platform} schema is deliberately minimal (§5.1), so a tenant-axis
 * statement that names {@code platform.} anyway dies with "relation does not exist" instead of landing
 * in a copy nobody reads.
 *
 * <p><strong>The one rule: always set on borrow, never reset on close.</strong> Reset-on-close is the
 * fragile half — it is skipped when {@code close()} throws, when the pool evicts the connection, and
 * when someone unwraps the proxy and closes the physical connection themselves. Unconditional
 * set-on-borrow makes it unnecessary, because the next borrower always overwrites. It is also what
 * survives the sharpest trap in this design: a raw {@code SET search_path} issued through a
 * {@link Statement} never sets Hikari's {@code DIRTY_BIT_SCHEMA}, so Hikari's own reset-on-close never
 * fires for it. A design that leaned on that reset would leak one tenant's schema to the next
 * borrower and look fine in every single-tenant test. The rule holds per pool: a remote pool's
 * connections are only ever borrowed through this class, so the same overwrite covers them.
 *
 * <p>Cost: one round trip per borrow (0.080 ms measured locally; 1–2.5 ms same-AZ is the number that
 * matters in production — and for a REMOTE pool it is that database's RTT, which is the price ADR 0011
 * §8 item 2 budgets). Plain {@code SET}, never {@code SET LOCAL}: the borrow happens before the
 * transaction opens, and a transaction-scoped path would be reset to the pool default at commit.
 */
final class TenantRoutingDataSource extends DelegatingDataSource {

    private final TenantDataSources dataSources;

    TenantRoutingDataSource(TenantDataSources dataSources) {
        // The delegate is the primary pool: it answers the metadata surface (getLoginTimeout, unwrap)
        // and is what an axis that routes nowhere borrows from. Routed borrows below never touch it
        // for a remote tenant.
        super(dataSources.primary());
        this.dataSources = dataSources;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Target target = resolveBeforeBorrowing();
        return pinned(target.pool().getConnection(), target.searchPath());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Target target = resolveBeforeBorrowing();
        return pinned(target.pool().getConnection(username, password), target.searchPath());
    }

    /** One borrow's destination, resolved whole before any connection is taken. */
    private record Target(DataSource pool, String searchPath) {}

    /**
     * <strong>Resolve everything first, take the connection second — the order is load-bearing.</strong>
     * A tenant axis resolves through {@link TenantRoutes#routeOf}, which on a cache miss reads
     * {@code platform.tenant_placement} over its own connection from the raw PRIMARY pool. Doing that
     * while already holding a connection from the pool being resolved means every thread in a saturated
     * pool waiting for a connection that only another waiting thread can release. Doing it first makes
     * the borrows sequential, so a miss costs one extra round trip and nothing else.
     *
     * <p>An unconfigured datasource name throws {@link UnknownTenantDataSourceException} out of the
     * borrow — the backstop, reached only by non-request paths, because {@code TenantSchemaFloor}
     * refuses the same tenant with a 503 at the edge and {@code TenantFanOut} withholds its home from
     * every sweep. What this must never do is quieter than throwing: guessing a pool is a misroute
     * (ADR 0010 §1), and a misroute is the failure this whole class exists to make impossible.
     */
    private Target resolveBeforeBorrowing() {
        Tenant tenant = TenantContext.current();
        return switch (tenant) {
            case Tenant.Org org -> {
                TenantRoutes.Route route = TenantRoutes.routeOf(org.orgId());
                yield new Target(dataSources.poolFor(route.datasourceName()),
                        TenantSchemas.tenantSearchPath(route.schema()));
            }
            // Both non-tenant states are primary by definition: PLATFORM because the platform tier
            // lives nowhere else (ADR 0011 §5), ABSENT because the poison state must fail on the
            // empty no_tenant schema of the database whose pool default everything else shares —
            // routing "nowhere" to a remote would make the failure depend on configuration.
            case Tenant.Platform ignored ->
                    new Target(dataSources.primary(), TenantSchemas.searchPathFor(tenant));
            case Tenant.Absent ignored ->
                    new Target(dataSources.primary(), TenantSchemas.searchPathFor(tenant));
        };
    }

    private static Connection pinned(Connection connection, String searchPath) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Not a bind parameter: SET takes no parameters. Every name in this string is either a
            // constant or has been through TenantSchemas.requireSiloSchema.
            statement.execute("SET search_path TO " + searchPath);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            // Hand back a connection whose schema we could not set and the caller runs against an
            // unknown path — the exact silent-corruption case this class exists to prevent. Returning
            // it to the pool is safe precisely because of the rule above: the next borrower overwrites.
            // Not returning it would leak a pool slot per failure.
            release(connection, failure);
            throw failure;
        }
    }

    private static void release(Connection connection, Exception failure) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
