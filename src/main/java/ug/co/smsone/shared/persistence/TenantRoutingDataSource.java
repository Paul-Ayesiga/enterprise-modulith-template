package ug.co.smsone.shared.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * Sets the {@code search_path} on every connection as it is borrowed, from
 * {@link TenantContext#current()} (ADR 0010 §3.1). This is the seam the whole tenancy design hangs
 * off: it is in front of Hibernate <em>and</em> in front of the 22 classes that hold a
 * {@code JdbcTemplate} or a {@code DataSource} directly, plus ShedLock and the Modulith event
 * registry — which is exactly why Hibernate's own {@code multi_tenant.schema_mapper} was rejected.
 * It covers Hibernate only, and treats a null tenant as "no multi-tenancy" rather than as an error.
 *
 * <p><strong>The one rule: always set on borrow, never reset on close.</strong> Reset-on-close is the
 * fragile half — it is skipped when {@code close()} throws, when the pool evicts the connection, and
 * when someone unwraps the proxy and closes the physical connection themselves. Unconditional
 * set-on-borrow makes it unnecessary, because the next borrower always overwrites. It is also what
 * survives the sharpest trap in this design: a raw {@code SET search_path} issued through a
 * {@link Statement} never sets Hikari's {@code DIRTY_BIT_SCHEMA}, so Hikari's own reset-on-close never
 * fires for it. A design that leaned on that reset would leak one tenant's schema to the next
 * borrower and look fine in every single-tenant test.
 *
 * <p>Cost: one round trip per borrow (0.080 ms measured locally; 1–2.5 ms same-AZ is the number that
 * matters in production). Plain {@code SET}, never {@code SET LOCAL}: the borrow happens before the
 * transaction opens, and a transaction-scoped path would be reset to the pool default at commit.
 */
final class TenantRoutingDataSource extends DelegatingDataSource {

    TenantRoutingDataSource(DataSource pool) {
        super(pool);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return pinned(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return pinned(super.getConnection(username, password));
    }

    private static Connection pinned(Connection connection) throws SQLException {
        String searchPath = TenantSchemas.searchPathFor(TenantContext.current());
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
