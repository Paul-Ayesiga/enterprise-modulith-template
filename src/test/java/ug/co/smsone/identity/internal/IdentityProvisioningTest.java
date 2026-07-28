package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.identity.internal.KeycloakUserAdminGateway.KeycloakUser;
import ug.co.smsone.identity.internal.UserAccessService.Decision;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Provisioning orchestration + the no-JIT access gate against REAL Postgres. The Keycloak admin
 * gateway is mocked here; the real Keycloak-admin round-trip is covered by the Phase-4 end-to-end IT.
 */
class IdentityProvisioningTest extends AbstractIntegrationTest {

    @MockitoBean
    private KeycloakUserAdminGateway keycloak;

    @Autowired
    private UserProvisioning provisioning;

    @Autowired
    private UserAccessService access;

    @Autowired
    private UserRepository users;

    @Test
    void provisionsKeycloakUserAndRecordsInvitedRow() {
        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.empty());
        given(keycloak.createUser(email, "Jane", "Doe")).willReturn(new KeycloakUser(subject, email));

        ProvisionedUser result = provisioning.provision(new ProvisionRequest(email, "Jane", "Doe"));

        assertThat(result.subject()).isEqualTo(subject);
        assertThat(result.alreadyExisted()).isFalse();
        assertThat(users.findBySubject(subject)).get()
                .extracting(User::getStatus).isEqualTo(ProvisioningStatus.INVITED);
        then(keycloak).should().issueTemporaryCredentials(subject); // new users get an invite
    }

    @Test
    void preExistingKeycloakAccountWithCredentialsGetsNoInvite() {
        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.of(new KeycloakUser(subject, email)));
        given(keycloak.hasCredentials(subject)).willReturn(true); // a real account with a password

        ProvisionedUser result = provisioning.provision(new ProvisionRequest(email, "Bob", "K"));

        assertThat(result.alreadyExisted()).isFalse(); // no local row yet — this provisions it
        assertThat(users.findBySubject(subject)).isPresent();
        then(keycloak).should(never()).issueTemporaryCredentials(subject); // never reset a real account
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

        ProvisionedUser result = provisioning.provision(new ProvisionRequest(email, "Jane", "Doe"));

        assertThat(result.alreadyExisted()).isFalse();
        assertThat(users.findBySubject(subject)).isPresent();
        then(keycloak).should().issueTemporaryCredentials(subject);
    }

    @Test
    void fullyProvisionedUserIsIdempotentAndSendsNoInvite() {
        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.empty());
        given(keycloak.createUser(email, null, null)).willReturn(new KeycloakUser(subject, email));
        provisioning.provision(new ProvisionRequest(email, null, null));

        // The account now exists in Keycloak; a re-invite finds it instead of re-creating.
        given(keycloak.findByEmail(email)).willReturn(Optional.of(new KeycloakUser(subject, email)));
        ProvisionedUser again = provisioning.provision(new ProvisionRequest(email, null, null));

        assertThat(again.alreadyExisted()).isTrue();
        assertThat(users.findAll().stream().filter(u -> subject.equals(u.getSubject()))).hasSize(1);
        then(keycloak).should().issueTemporaryCredentials(subject); // exactly once, from the first call
    }

    @Test
    void disabledUserIsDeniedByBothAuthorizeAndPeek() {
        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.empty());
        given(keycloak.createUser(email, null, null)).willReturn(new KeycloakUser(subject, email));
        provisioning.provision(new ProvisionRequest(email, null, null));

        User user = users.findBySubject(subject).orElseThrow();
        user.disable();
        users.save(user);

        assertThat(access.authorize(subject)).isEqualTo(Decision.DISABLED);
        // peek() backs the lenient GET /me path — DISABLED must be a hard stop there too.
        assertThat(access.peek(subject)).isEqualTo(Decision.DISABLED);
    }

    @Test
    void gateDeniesUnprovisionedThenActivatesInvitedOnFirstHit() {
        assertThat(access.authorize("nobody-" + UUID.randomUUID())).isEqualTo(Decision.NOT_PROVISIONED);

        String subject = "kc-" + UUID.randomUUID();
        String email = subject + "@smsone.co.ug";
        given(keycloak.findByEmail(email)).willReturn(Optional.empty());
        given(keycloak.createUser(email, null, null)).willReturn(new KeycloakUser(subject, email));
        provisioning.provision(new ProvisionRequest(email, null, null));

        assertThat(access.authorize(subject)).isEqualTo(Decision.ALLOWED);
        assertThat(users.findBySubject(subject)).get()
                .extracting(User::getStatus).isEqualTo(ProvisioningStatus.ACTIVE);
    }
}
