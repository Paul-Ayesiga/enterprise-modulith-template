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
 * The routing seam against a real pool and a real Postgres (ADR 0010 §3.1).
 *
 * <p>Through Phase 1 the two axes resolved to the same schema, so all this class could pin was the
 * mechanism: the path is set on <em>every</em> borrow, absence resolves to an empty schema rather than
 * to whatever the connection was last left on, and pinning a tenant inside a transaction fails instead
 * of silently doing nothing. Phase 2 moved the tables, so the same mechanism now has a consequence that
 * can be read off the database — the axis decides which schema answers, and
 * {@link #eachAxisReachesItsOwnTierAndAQualifiedNameReachesThePlatformFromEither()} is the test that
 * says so with real tables rather than with a string comparison.
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

    /**
     * One table from EACH tier, because since Phase 2 they fail differently and a single probe would
     * only catch one of the two fallbacks. If absence ever started resolving to {@code platform},
     * {@code organization} would resolve and {@code org_role} would not; if it resolved to
     * {@code tenant_pool}, exactly the reverse. Naming one table would leave half of that hole open,
     * which is the shape of a fail-closed test that quietly stops closing.
     */
    @Test
    void withoutATenantAnUnqualifiedTableIsUnreachableRatherThanSomebodyElsesRows() {
        TenantContext.clear();

        // Read off the ROOT cause, not the message. Since Spring Framework 6 a BadSqlGrammarException's
        // own message is task + SQL and nothing else — the driver's sentence is only in the cause — so
        // `hasMessageContaining("does not exist")` on the wrapper would be false however the statement
        // failed. The relation is named as well, so this stays an assertion about THAT table being
        // unreachable rather than about some SQL error having happened.
        assertThatThrownBy(() -> jdbcTemplate.queryForObject("select count(*) from organization", Long.class))
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause()
                .hasMessageContaining("relation \"organization\" does not exist");

        assertThatThrownBy(() -> jdbcTemplate.queryForObject("select count(*) from org_role", Long.class))
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause()
                .hasMessageContaining("relation \"org_role\" does not exist");
    }

    /**
     * The Phase 2 split, proved against real tables rather than against the router's own string. A
     * tenant-tier table is reached BARE and only resolves on a tenant axis; a platform-tier one is
     * reached BY NAME and therefore resolves from either — and that second property is what lets a
     * write spanning both tiers stay ONE pinned span on ONE connection, and so one transaction.
     *
     * <p>The bare platform-tier read failing on the tenant axis is the important negative. It is not a
     * theoretical tidiness: that is precisely what a forgotten {@code platform.} prefix does at runtime,
     * and every shape it takes downstream is quiet — an {@code UPDATE} matching zero rows, a
     * {@code NOT EXISTS} guard excluding nothing. {@code PlatformSchemaQualificationTest} is what stops
     * one being written; this is what proves the database really does behave the way that test assumes.
     */
    @Test
    void eachAxisReachesItsOwnTierAndAQualifiedNameReachesThePlatformFromEither() {
        TenantContext.set(UUID.randomUUID());
        assertThat(jdbcTemplate.queryForObject("select count(*) from org_role", Long.class))
                .as("a tenant-tier table is bare and the tenant axis is what places it")
                .isNotNull();
        assertThatThrownBy(() -> jdbcTemplate.queryForObject("select count(*) from organization", Long.class))
                .as("`platform` is on no tenant's path, so a forgotten qualifier resolves to nothing")
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause()
                .hasMessageContaining("relation \"organization\" does not exist");
        assertThat(jdbcTemplate.queryForObject("select count(*) from platform.organization", Long.class))
                .as("named, the platform tier is reachable from a tenant-pinned connection — which is "
                        + "what keeps a cross-tier write in one transaction")
                .isNotNull();

        TenantContext.set(Tenant.PLATFORM);
        assertThat(jdbcTemplate.queryForObject("select count(*) from platform.organization", Long.class))
                .isNotNull();
        assertThatThrownBy(() -> jdbcTemplate.queryForObject("select count(*) from org_role", Long.class))
                .as("the platform axis must not reach the pool, or a promoted tenant's rows would be "
                        + "read out of the schema it was promoted out of")
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause()
                .hasMessageContaining("relation \"org_role\" does not exist");
    }

    @Test
    void aPinnedAxisPutsTheExtensionSchemaOnThePathSoTrigramSearchStillResolves() throws SQLException {
        TenantContext.set(UUID.randomUUID());
        Borrow tenant = borrow();
        assertThat(tenant.searchPath()).isEqualTo("tenant_pool, ext");
        // current_schema() is the FIRST resolvable element, so it is what pins the order: `ext, <tenant>`
        // would satisfy any assertion about the path containing both and would then create every
        // unqualified table in the extension schema.
        assertThat(tenant.currentSchema()).isEqualTo("tenant_pool");

        TenantContext.set(Tenant.PLATFORM);
        Borrow platform = borrow();
        assertThat(platform.searchPath()).isEqualTo("platform, ext");
        assertThat(platform.currentSchema()).isEqualTo("platform");

        // pg_trgm lives in `ext` since V54 moved it off `public`. SearchQueryService's word_similarity()
        // and V22's gin_trgm_ops resolve only because that schema is on the path — the one thing a
        // single-element path (what Hibernate's schema_mapper would set) silently breaks.
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
            assertThat(queryString(first, "select current_setting('search_path')")).isEqualTo("platform, ext");
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
