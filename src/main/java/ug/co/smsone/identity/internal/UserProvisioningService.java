package ug.co.smsone.identity.internal;

import java.time.Clock;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.identity.internal.KeycloakUserAdminGateway.KeycloakUser;

/**
 * Provisions a user: create-or-reuse the Keycloak account, invite it if it has no credentials yet,
 * then record the local {@code app_user} row as {@code INVITED}. Keycloak calls run OUTSIDE the local
 * transaction; every step is idempotent, so a retry after a mid-flight failure finishes the job —
 * including re-sending the invite when the account was created but the credential e-mail failed
 * (an account must never be stranded credential-less with the retry reporting success).
 */
@Service
class UserProvisioningService implements UserProvisioning {

    private final KeycloakUserAdminGateway keycloak;
    private final UserRepository users;
    private final Clock clock;

    UserProvisioningService(KeycloakUserAdminGateway keycloak, UserRepository users, Clock clock) {
        this.keycloak = keycloak;
        this.users = users;
        this.clock = clock;
    }

    @Override
    public ProvisionedUser provision(ProvisionRequest request) {
        Optional<KeycloakUser> existing = keycloak.findByEmail(request.email());
        KeycloakUser kcUser = existing.orElseGet(
                () -> keycloak.createUser(request.email(), request.firstName(), request.lastName()));
        boolean alreadyProvisioned = users.findBySubject(kcUser.id()).isPresent();
        if (!alreadyProvisioned) {
            // Invite exactly when the account has no credentials yet: a fresh create, or a retry
            // after the first invite attempt failed. A genuinely pre-existing account (has a
            // password) is never sent a forced credential-reset invite.
            if (!keycloak.hasCredentials(kcUser.id())) {
                keycloak.issueTemporaryCredentials(kcUser.id());
            }
            saveLocalUser(kcUser.id(), request.email());
        }
        return new ProvisionedUser(kcUser.id(), request.email(), alreadyProvisioned);
    }

    private void saveLocalUser(String subject, String email) {
        try {
            users.save(User.invited(subject, email, clock.instant())); // publishes UserProvisioned
        } catch (DataIntegrityViolationException ex) {
            // Concurrent provision of the same person: the winner's row IS the idempotent outcome.
            if (users.findBySubject(subject).isEmpty()) {
                throw ex; // not the duplicate-subject race after all
            }
        }
    }
}
