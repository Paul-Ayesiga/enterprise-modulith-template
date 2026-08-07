package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.identity.internal.KeycloakUserAdminGateway.KeycloakUser;
import ug.co.smsone.identity.internal.PersonAccessService.Decision;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Provisioning orchestration + the no-JIT access gate against REAL Postgres. The Keycloak admin
 * gateway is mocked here; the real Keycloak-admin round-trip is covered by the Phase-4 end-to-end IT.
 *
 * <p>The order under test inverted with the schema: the {@code person} row is created FIRST and the
 * {@code external_identity} link LAST, so the link — not the local row — is now what makes a second
 * call idempotent, and a person is looked up by their id rather than by a provider's subject.
 */
class IdentityProvisioningTest extends AbstractIntegrationTest {

    @MockitoBean
    private KeycloakUserAdminGateway keycloak;

    @Autowired
    private PersonProvisioning provisioning;

    @Autowired
    private PersonAccessService access;

    @Autowired
    private PersonRepository persons;

    @Autowired
    private PersonResolver resolver;

    @Test
    void provisionsKeycloakUserAndRecordsInvitedRow() {
        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.empty());
        given(keycloak.createUser(email, "Jane", "Doe")).willReturn(new KeycloakUser(subject, email));

        ProvisionedPerson result = provisioning.provision(new ProvisionRequest(email, "Jane", "Doe"));

        // The subject is no longer the identity: it resolves TO one, through external_identity.
        assertThat(resolver.personIdOf(subject)).contains(result.personId());
        assertThat(result.alreadyExisted()).isFalse();
        assertThat(persons.findById(result.personId())).get()
                .extracting(Person::getStatus).isEqualTo(ProvisioningStatus.INVITED);
        then(keycloak).should().issueTemporaryCredentials(eq(subject),
                eq(java.util.List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"))); // new users get an invite
    }

    @Test
    void requireTotpAddsConfigureTotpToTheInvite() {
        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.empty());
        given(keycloak.createUser(email, "Amina", "K")).willReturn(new KeycloakUser(subject, email));

        provisioning.provision(new ProvisionRequest(email, "Amina", "K", true));

        // The org's MFA policy reaches first login: the action link enrolls TOTP too.
        then(keycloak).should().issueTemporaryCredentials(eq(subject),
                eq(java.util.List.of("UPDATE_PASSWORD", "VERIFY_EMAIL", "CONFIGURE_TOTP")));
    }

    @Test
    void preExistingKeycloakAccountWithCredentialsGetsNoInvite() {
        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.of(new KeycloakUser(subject, email)));
        given(keycloak.hasCredentials(subject)).willReturn(true); // a real account with a password

        ProvisionedPerson result = provisioning.provision(new ProvisionRequest(email, "Bob", "K"));

        assertThat(result.alreadyExisted()).isFalse(); // no link yet — this provisions it
        assertThat(resolver.personIdOf(subject)).contains(result.personId());
        then(keycloak).should(never()).issueTemporaryCredentials(eq(subject), anyList()); // never reset a real account
    }

    @Test
    void retryAfterFailedInviteReissuesCredentials() {
        // First attempt created the Keycloak account but the credential e-mail failed: account
        // exists, no credentials, no local row. The retry must re-send the invite — not silently
        // report success and strand a credential-less account.
        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.of(new KeycloakUser(subject, email)));
        given(keycloak.hasCredentials(subject)).willReturn(false);

        ProvisionedPerson result = provisioning.provision(new ProvisionRequest(email, "Jane", "Doe"));

        assertThat(result.alreadyExisted()).isFalse();
        assertThat(resolver.personIdOf(subject)).contains(result.personId());
        then(keycloak).should().issueTemporaryCredentials(eq(subject), anyList());
    }

    @Test
    void fullyProvisionedUserIsIdempotentAndSendsNoInvite() {
        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.empty());
        given(keycloak.createUser(email, null, null)).willReturn(new KeycloakUser(subject, email));
        ProvisionedPerson first = provisioning.provision(new ProvisionRequest(email, null, null));

        // The account now exists in Keycloak; a re-invite finds it instead of re-creating.
        given(keycloak.findByEmail(email)).willReturn(Optional.of(new KeycloakUser(subject, email)));
        ProvisionedPerson again = provisioning.provision(new ProvisionRequest(email, null, null));

        // Same person, not a second one — the link is what the second call finds, and the advisory
        // lock around find-or-create is what stops one human becoming two people.
        assertThat(again.personId()).isEqualTo(first.personId());
        assertThat(again.alreadyExisted()).isTrue();
        then(keycloak).should().issueTemporaryCredentials(eq(subject), anyList()); // exactly once, from the first call
    }

    @Test
    void disabledUserIsDeniedByBothAuthorizeAndPeek() {
        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.empty());
        given(keycloak.createUser(email, null, null)).willReturn(new KeycloakUser(subject, email));
        UUID personId = provisioning.provision(new ProvisionRequest(email, null, null)).personId();

        Person person = persons.findById(personId).orElseThrow();
        person.disable(java.time.Instant.now());
        persons.save(person);

        assertThat(access.authorize(personId)).isEqualTo(Decision.DISABLED);
        // peek() backs the lenient GET /me path — DISABLED must be a hard stop there too.
        assertThat(access.peek(personId)).isEqualTo(Decision.DISABLED);
    }

    @Test
    void gateDeniesUnprovisionedThenActivatesInvitedOnFirstHit() {
        // An unlinked subject reaches no person, and identity — not the edge — says which kind of
        // absence that is: never linked, so onboarding is allowed to render.
        assertThat(access.explainAbsence("nobody-" + UUID.randomUUID())).isEqualTo(Decision.NOT_PROVISIONED);

        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.empty());
        given(keycloak.createUser(email, null, null)).willReturn(new KeycloakUser(subject, email));
        UUID personId = provisioning.provision(new ProvisionRequest(email, null, null)).personId();

        assertThat(access.authorize(personId)).isEqualTo(Decision.ALLOWED);
        assertThat(persons.findById(personId)).get()
                .extracting(Person::getStatus).isEqualTo(ProvisioningStatus.ACTIVE);
    }
}
