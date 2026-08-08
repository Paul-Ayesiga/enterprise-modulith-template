package ug.co.smsone.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.NoTenantAxis;

/**
 * The routing seam against a real pool and a real Postgres (ADR 0010 §3.1, Phase 1 gate).
 *
 * <p>Phase 1 moves no table, so the tenant and platform axes both resolve to today's schema and there
 * is no cross-tenant read to catch yet. What is provable now, and what this class pins, is the
 * mechanism itself: the path is set on <em>every</em> borrow, absence resolves to an empty schema
 * rather than to whatever the connection was last left on, and pinning a tenant inside a transaction
 * fails instead of silently doing nothing.
 *
 * <p>Every test below states the axis it wants as its first line, so nothing here depends on what the
 * harness would have left on the thread — but the opt-out is not therefore decoration. This is the one
 * class in the suite where absence is the SUBJECT, and a test added later that forgot its first line
 * would, under a harness pin, read as a statement about the unpinned path while quietly making a
 * statement about the platform one. Opting out means that test fails instead.
 */
@NoTenantAxis("absence is the subject here — a harness pin would make a forgotten clear() look like a "
        + "passing fail-closed assertion, which is the exact failure this class exists to catch")
class TenantRoutingDataSourceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HikariDataSource poolDataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearTheThread() {
        TenantContext.clear();
    }

    @Test
    void everyDataSourceInjectionPointGetsTheRouterAndFlywayGetsTheRawPool() {
        // The two-bean split is the design, not an accident: Flyway through the router would migrate
        // with no tenant pinned and fail on "Unable to determine current schema as search_path is empty".
        assertThat(dataSource).isInstanceOf(TenantRoutingDataSource.class);
        assertThat(flyway.getConfiguration().getDataSource()).isSameAs(poolDataSource).isNotSameAs(dataSource);
    }

    @Test
    void aConnectionBorrowedWithNoTenantPinnedLandsInTheEmptySchema() throws SQLException {
        TenantContext.clear();

        Borrow borrow = borrow();

        assertThat(borrow.searchPath()).isEqualTo("no_tenant");
        assertThat(borrow.currentSchema())
                .as("no_tenant must exist and be first on the path, or current_schema() is null")
                .isEqualTo("no_tenant");
    }

    @Test
    void withoutATenantAnUnqualifiedTableIsUnreachableRatherThanSomebodyElsesRows() {
        TenantContext.clear();

        assertThatThrownBy(() -> jdbcTemplate.queryForObject("select count(*) from organization", Long.class))
                .isInstanceOf(BadSqlGrammarException.class)
                // Read off the ROOT cause, not the message. Since Spring Framework 6 a
                // BadSqlGrammarException's own message is task + SQL and nothing else — the driver's
                // sentence is only in the cause — so `hasMessageContaining("does not exist")` on the
                // wrapper would be false however the statement failed, and would keep being false if
                // absence ever started resolving to a schema where the table DOES exist. The relation is
                // named as well, so this stays an assertion about `organization` being unreachable rather
                // than about some SQL error having happened.
                .rootCause()
                .hasMessageContaining("relation \"organization\" does not exist");
    }

    @Test
    void aPinnedAxisPutsTheExtensionSchemaOnThePathSoTrigramSearchStillResolves() throws SQLException {
        TenantContext.set(UUID.randomUUID());
        assertThat(borrow().searchPath()).isEqualTo("public, ext");

        TenantContext.set(Tenant.PLATFORM);
        assertThat(borrow().searchPath()).isEqualTo("public, ext");

        // pg_trgm lives in `ext`, off `public`. SearchQueryService's word_similarity() and V22's
        // gin_trgm_ops resolve only because that schema is on the path — the one thing a single-element
        // path (what Hibernate's schema_mapper would set) silently breaks.
        assertThat(jdbcTemplate.queryForObject("select word_similarity('smson', 'smsone')", Float.class))
                .isNotNull()
                .isGreaterThan(0.0f);
    }

    @Test
    void theNextBorrowerOverwritesWhateverThePreviousOneLeftOnTheConnection() throws SQLException {
        int backendPid;
        TenantContext.setPlatform();
        try (Connection first = dataSource.getConnection()) {
            backendPid = queryInt(first, "select pg_backend_pid()");
            assertThat(queryString(first, "select current_setting('search_path')")).isEqualTo("public, ext");
            // Leave it dirty on the way back to the pool, the way a caller that set its own path would.
            // Nothing cleans this up: a raw SET never flips Hikari's DIRTY_BIT_SCHEMA, so its
            // reset-on-close does not fire. Correctness comes from the next borrow, not from a reset.
            try (Statement statement = first.createStatement()) {
                statement.execute("SET search_path TO pg_catalog");
            }
        }

        TenantContext.clear();
        try (Connection second = dataSource.getConnection()) {
            assertThat(queryInt(second, "select pg_backend_pid()"))
                    .as("the pool must hand back the same physical connection, or this proves nothing")
                    .isEqualTo(backendPid);
            assertThat(queryString(second, "select current_setting('search_path')")).isEqualTo("no_tenant");
        }
    }

    @Test
    void pinningATenantInsideARealTransactionThrowsRatherThanCorruptingTheWrite() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status ->
                // The transaction already borrowed and pinned a connection; this call cannot change the
                // schema its statements run against. Throwing is what turns a silent misrouted write —
                // the worst failure this design can produce — into an obvious one.
                assertThatThrownBy(() -> TenantContext.set(UUID.randomUUID()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("BEFORE the transaction opens"));
    }

    private record Borrow(String currentSchema, String searchPath) {}

    private Borrow borrow() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "select current_schema(), current_setting('search_path')")) {
            row.next();
            return new Borrow(row.getString(1), row.getString(2));
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getInt(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }
}
