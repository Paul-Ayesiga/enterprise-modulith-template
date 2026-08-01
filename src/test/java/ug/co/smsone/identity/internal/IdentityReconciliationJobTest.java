package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.identity.internal.IdentityReconciliationProperties.Action;
import ug.co.smsone.identity.internal.KeycloakUserAdminGateway.AccountPresence;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The only scheduled job that can revoke access, so most of these tests assert what it REFUSES to do.
 *
 * <p>Real Postgres; the Keycloak gateway is mocked because the behaviour under test is how the job
 * reacts to each of the three answers, and two of them (unreachable, mass-404) are states a live
 * Keycloak will not produce on demand. {@code KeycloakProvisioningIntegrationTest} pins that
 * {@code accountPresence} reports those answers correctly against the real thing.
 */
class IdentityReconciliationJobTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ug.co.smsone.shared.audit.AuditLog auditLog;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoBean
    private KeycloakUserAdminGateway keycloak;

    private String prefix;

    @BeforeEach
    void freshFixture() {
        prefix = "recon-" + UUID.randomUUID() + "-";
    }

    /** Provisioned two hours ago, so it is past the one-hour grace period by default. */
    private User seed(String suffix, ProvisioningStatus status) {
        String subject = prefix + suffix;
        User user = users.save(User.invited(subject, subject + "@smsone.co.ug",
                Instant.now().minus(Duration.ofHours(2))));
        if (status != ProvisioningStatus.INVITED) {
            jdbc.update("update app_user set status = ? where subject = ?", status.name(), subject);
        }
        // provisioned_at is not updatable through the entity; age it directly so the grace period applies.
        jdbc.update("update app_user set provisioned_at = now() - interval '2 hours' where subject = ?", subject);
        return user;
    }

    private IdentityReconciliationJob jobWith(Action action, double maxOrphanRatio) {
        return new IdentityReconciliationJob(users, keycloak,
                new IdentityReconciliationProperties(action, Duration.ofHours(1), maxOrphanRatio, 500),
                auditLog, transactions, Clock.system(ZoneOffset.UTC));
    }

    private ProvisioningStatus statusOf(String suffix) {
        return ProvisioningStatus.valueOf(jdbc.queryForObject(
                "select status from app_user where subject = ?", String.class, prefix + suffix));
    }

    /** Straight from the table, so the real AuditLogImpl is what is being exercised. */
    private List<String> auditActionsFor(String suffix) {
        return jdbc.queryForList("select action from audit_log where target = ?",
                String.class, prefix + suffix);
    }

    @Test
    void anAccountDeletedInKeycloakIsDisabledAndAudited() {
        seed("gone", ProvisioningStatus.ACTIVE);
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.ABSENT);

        var result = jobWith(Action.DISABLE, 1.0).run();

        assertThat(result.orphaned()).isPositive();
        assertThat(statusOf("gone")).isEqualTo(ProvisioningStatus.DISABLED);
        assertThat(auditActionsFor("gone")).contains("identity.user_disabled_by_reconciliation");
    }

    /**
     * Regression: a pass walks EVERY candidate in keyset pages. The old implementation loaded the
     * whole table unordered and truncated to batch-size, so with more candidates than batch-size it
     * re-examined an arbitrary head nightly and nothing guaranteed the tail was ever reached.
     */
    @Test
    void aPassExaminesEveryCandidateAcrossMultiplePages() {
        seed("page-a", ProvisioningStatus.ACTIVE);
        seed("page-b", ProvisioningStatus.ACTIVE);
        seed("page-c", ProvisioningStatus.ACTIVE);
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.PRESENT);

        var result = new IdentityReconciliationJob(users, keycloak,
                new IdentityReconciliationProperties(Action.REPORT, Duration.ofHours(1), 1.0, 1),
                auditLog, transactions, Clock.system(ZoneOffset.UTC)).run();

        assertThat(result.examined())
                .as("a page size of 1 must still walk all three seeded candidates")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void anAccountStillInKeycloakIsUntouched() {
        seed("live", ProvisioningStatus.ACTIVE);
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.PRESENT);

        jobWith(Action.DISABLE, 1.0).run();

        assertThat(statusOf("live")).isEqualTo(ProvisioningStatus.ACTIVE);
    }

    /**
     * The property that makes the job safe to schedule: a lookup that FAILED must never be read as a
     * deletion. Without the tri-state, one Keycloak outage revokes the entire user base.
     */
    @Test
    void anInconclusiveLookupNeverRevokesAnybody() {
        seed("unreachable", ProvisioningStatus.ACTIVE);
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.UNKNOWN);

        var result = jobWith(Action.DISABLE, 1.0).run();

        assertThat(result.unknown()).isPositive();
        assertThat(result.orphaned()).isZero();
        assertThat(result.acted()).isZero();
        assertThat(statusOf("unreachable")).isEqualTo(ProvisioningStatus.ACTIVE);
    }

    /**
     * The misconfiguration breaker. A wrong realm makes every per-row lookup a legitimate 404, so the
     * job's own answers cannot distinguish it — only the PROPORTION can.
     */
    @Test
    void aMassDisappearanceIsTreatedAsMisconfigurationAndChangesNothing() {
        seed("a", ProvisioningStatus.ACTIVE);
        seed("b", ProvisioningStatus.ACTIVE);
        seed("c", ProvisioningStatus.ACTIVE);
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.ABSENT);

        var result = jobWith(Action.DISABLE, 0.10).run();

        assertThat(result.abandoned()).isTrue();
        assertThat(result.acted()).isZero();
        assertThat(statusOf("a")).isEqualTo(ProvisioningStatus.ACTIVE);
        assertThat(statusOf("b")).isEqualTo(ProvisioningStatus.ACTIVE);
        assertThat(statusOf("c")).isEqualTo(ProvisioningStatus.ACTIVE);
    }

    /** The shipped default: see what it would do before letting it do it. */
    @Test
    void reportModeFindsTheOrphanWithoutRevokingIt() {
        seed("watched", ProvisioningStatus.ACTIVE);
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.ABSENT);

        var result = jobWith(Action.REPORT, 1.0).run();

        assertThat(result.orphaned()).isPositive();
        assertThat(result.acted()).isZero();
        assertThat(statusOf("watched")).isEqualTo(ProvisioningStatus.ACTIVE);
    }

    /** An already-revoked row must not be re-disabled, or the audit trail churns every night. */
    @Test
    void anAlreadyDisabledAccountIsNotVisitedAgain() {
        seed("done", ProvisioningStatus.DISABLED);
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.ABSENT);

        jobWith(Action.DISABLE, 1.0).run();

        assertThat(auditActionsFor("done")).isEmpty();
    }

    /** Young rows may still be mid-provisioning; the grace period keeps the job off them. */
    @Test
    void anAccountInsideTheGracePeriodIsNotExamined() {
        String subject = prefix + "fresh";
        users.save(User.invited(subject, subject + "@smsone.co.ug", Instant.now()));
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.ABSENT);

        jobWith(Action.DISABLE, 1.0).run();

        // Asserting the status IS the test: without the grace period this row would be ABSENT and
        // therefore disabled. A count assertion would be fragile — aged rows from the tests above are
        // still in the table and legitimately get examined.
        assertThat(statusOf("fresh")).isNotEqualTo(ProvisioningStatus.DISABLED);
    }
}
