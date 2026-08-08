package ug.co.smsone.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * The opt-out, proved to opt out.
 *
 * <p>Without this class {@link NoTenantAxis} could stop being read entirely — {@link
 * TenantAxisExtension} would pin unconditionally, every class on {@code TenantAxisHarnessTest}'s roster
 * would go quietly vacuous, and no other test in the suite would fail. That is the same shape of bug
 * the roster exists to prevent, one level down: a safety mechanism whose own failure is silent.
 *
 * <p>It is also the worked example the annotation's javadoc points at — what an unpinned class looks
 * like, and how it seeds anyway.
 */
@NoTenantAxis("proves the opt-out is honoured — if the extension ignored the annotation every class "
        + "on the roster would go vacuous with nothing else in the suite failing")
class TenantAxisOptOutTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theHarnessLeavesThisThreadUnpinned() {
        assertThat(TenantContext.current()).isEqualTo(Tenant.ABSENT);
    }

    @Test
    void andThatAbsenceReachesTheConnectionRatherThanStoppingAtTheThreadLocal() {
        // The assertion above is about a ThreadLocal; this one is about what the database sees. They can
        // disagree — a stray pin in a DataSource wrapper, a pooled connection left on someone's path —
        // and only the second one is what the fail-closed layers actually rest on.
        assertThatThrownBy(() -> jdbc.queryForObject("select count(*) from organization", Long.class))
                .isInstanceOf(BadSqlGrammarException.class)
                // The ROOT cause, because a BadSqlGrammarException's own message is task + SQL and
                // carries nothing about why Postgres refused (Spring Framework 6 stopped appending the
                // cause). Asserting the relation by name keeps this about `organization` being out of
                // reach rather than about any SQL failure at all.
                .rootCause()
                .hasMessageContaining("relation \"organization\" does not exist");
    }

    @Test
    void seedingStillWorksWhenItDeclaresItsOwnAxisAndGivesTheAbsenceBack() {
        Long organizations = TenantContext.callAsPlatform(
                () -> jdbc.queryForObject("select count(*) from organization", Long.class));

        assertThat(organizations).isNotNull();
        assertThat(TenantContext.current())
                .as("callAsPlatform restores absence, so the window the class is actually about stays "
                        + "unpinned — a bare setPlatform() here would silently pin the rest of the method")
                .isEqualTo(Tenant.ABSENT);
    }
}
