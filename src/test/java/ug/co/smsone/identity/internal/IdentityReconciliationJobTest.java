package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
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
    private PersonRepository persons;

    @Autowired
    private ExternalIdentityRepository identities;

    @Autowired
    private PersonResolver resolver;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ug.co.smsone.shared.audit.AuditLog auditLog;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoBean
    private KeycloakUserAdminGateway keycloak;

    /**
     * Suffix → the person it seeded. The job now walks people and audits their {@code person.id}, so a
     * test cannot name its fixture by a subject string any more — it has to remember the id.
     */
    private Map<String, UUID> seeded;

    @BeforeEach
    void freshFixture() {
        seeded = new HashMap<>();
    }

    /** Invited two hours ago, so it is past the one-hour grace period by default. */
    private UUID seed(String suffix, ProvisioningStatus status) {
        UUID personId = seedAged(suffix, Duration.ofHours(2));
        if (status != ProvisioningStatus.INVITED) {
            // Anything past INVITED went through activation, so activated_at is set too: a person who is
            // ACTIVE with a null activated_at is a row the real provisioning path never writes, and the
            // job's own disable() is the thing that stamps disabled_at.
            jdbc.update("update person set status = ?, activated_at = invited_at where id = ?",
                    status.name(), personId);
        }
        if (status == ProvisioningStatus.DISABLED) {
            jdbc.update("update person set disabled_at = now() where id = ?", personId);
        }
        return personId;
    }

    /**
     * A linked person whose {@code invited_at} is aged directly in SQL — that is the column the job's
     * candidate scan filters and orders on ({@code idx_person_scan}), and the entity maps it
     * {@code updatable = false}.
     */
    private UUID seedAged(String suffix, Duration age) {
        UUID personId = persons.save(Person.invited(PersonName.ofParts(null, null),
                Instant.now().minus(age))).getId();
        identities.save(ExternalIdentity.link(personId, IdentityProvider.KEYCLOAK, resolver.keycloakIssuer(),
                "kc-" + personId, null, Instant.now()));
        jdbc.update("update person set invited_at = now() - make_interval(secs => ?) where id = ?",
                (double) age.toSeconds(), personId);
        seeded.put(suffix, personId);
        return personId;
    }

    private IdentityReconciliationJob jobWith(Action action, double maxOrphanRatio) {
        return new IdentityReconciliationJob(persons, resolver, keycloak,
                new IdentityReconciliationProperties(action, Duration.ofHours(1), maxOrphanRatio, 500),
                auditLog, transactions, Clock.system(ZoneOffset.UTC));
    }

    private ProvisioningStatus statusOf(String suffix) {
        return ProvisioningStatus.valueOf(jdbc.queryForObject(
                "select status from person where id = ?", String.class, seeded.get(suffix)));
    }

    /** Straight from the table, so the real AuditLogImpl is what is being exercised. */
    private List<String> auditActionsFor(String suffix) {
        return jdbc.queryForList("select action from audit_log where target = ?",
                String.class, seeded.get(suffix).toString());
    }

    @Test
    void anAccountDeletedInKeycloakIsDisabledAndAudited() {
        seed("gone", ProvisioningStatus.ACTIVE);
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.ABSENT);

        var result = jobWith(Action.DISABLE, 1.0).run();

        assertThat(result.orphaned()).isPositive();
        assertThat(statusOf("gone")).isEqualTo(ProvisioningStatus.DISABLED);
        assertThat(auditActionsFor("gone")).contains("identity.person_disabled_by_reconciliation");
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

        var result = new IdentityReconciliationJob(persons, resolver, keycloak,
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
        seedAged("fresh", Duration.ZERO);
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.ABSENT);

        jobWith(Action.DISABLE, 1.0).run();

        // Asserting the status IS the test: without the grace period this row would be ABSENT and
        // therefore disabled. A count assertion would be fragile — aged rows from the tests above are
        // still in the table and legitimately get examined.
        assertThat(statusOf("fresh")).isNotEqualTo(ProvisioningStatus.DISABLED);
    }
}
