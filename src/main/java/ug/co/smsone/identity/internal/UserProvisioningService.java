package ug.co.smsone.identity.internal;

import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.identity.internal.KeycloakUserAdminGateway.KeycloakUser;

/**
 * Provisions a user: create-or-reuse the Keycloak account (with temporary credentials for new users),
 * then record the local {@code app_user} row as {@code INVITED}. Keycloak calls run OUTSIDE the local
 * transaction; a mid-flight failure leaves at most an INVITED row and no access.
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
        KeycloakUser kcUser;
        boolean created;
        if (existing.isPresent()) {
            kcUser = existing.get();
            created = false;
        } else {
            kcUser = keycloak.createUser(request.email(), request.firstName(), request.lastName());
            keycloak.issueTemporaryCredentials(kcUser.id()); // only newly-created users get an invite
            created = true;
        }
        upsertLocalUser(kcUser.id(), request.email());
        return new ProvisionedUser(kcUser.id(), request.email(), !created);
    }

    @Transactional
    void upsertLocalUser(String subject, String email) {
        if (users.findBySubject(subject).isEmpty()) {
            users.save(User.invited(subject, email, clock.instant())); // publishes UserProvisioned
        }
    }
}
