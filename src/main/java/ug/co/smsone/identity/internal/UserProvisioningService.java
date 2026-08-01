package ug.co.smsone.identity.internal;

import java.time.Clock;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.identity.internal.KeycloakUserAdminGateway.KeycloakUser;
import ug.co.smsone.shared.audit.AuditLog;

/**
 * Provisions a user: create-or-reuse the Keycloak account, grant it the baseline realm role, invite it
 * if it has no credentials yet, then record the local {@code app_user} row as {@code INVITED}. What it
 * grants is deliberately the floor — org authority comes from the DB (membership → role → permissions)
 * and platform authority is never granted here at all. Keycloak calls run OUTSIDE the local
 * transaction; every step is idempotent, so a retry after a mid-flight failure finishes the job —
 * including re-sending the invite when the account was created but the credential e-mail failed
 * (an account must never be stranded credential-less with the retry reporting success).
 */
@Service
class UserProvisioningService implements UserProvisioning {

    private final KeycloakUserAdminGateway keycloak;
    private final UserRepository users;
    private final Clock clock;
    private final AuditLog auditLog;
    private final ProvisioningProperties properties;
    private final TransactionTemplate transactionTemplate;

    UserProvisioningService(KeycloakUserAdminGateway keycloak, UserRepository users, Clock clock,
            AuditLog auditLog, ProvisioningProperties properties, TransactionTemplate transactionTemplate) {
        this.keycloak = keycloak;
        this.users = users;
        this.clock = clock;
        this.auditLog = auditLog;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public ProvisionedUser provision(ProvisionRequest request) {
        Optional<KeycloakUser> existing = keycloak.findByEmail(request.email());
        KeycloakUser kcUser = existing.orElseGet(
                () -> keycloak.createUser(request.email(), request.firstName(), request.lastName()));
        boolean alreadyProvisioned = users.findBySubject(kcUser.id()).isPresent();
        if (!alreadyProvisioned) {
            // Baseline realm role first: it is the cheapest step and has no outward side effect, so a
            // failure here costs nobody an e-mail. Everything before saveLocalUser() is idempotent and
            // leaves no app_user row, which is what makes a retry able to finish the job.
            // NOTE: only the configured baseline — provisioning can never grant platform authority
            // (ProvisioningProperties rejects a platform role at startup).
            if (properties.grantsDefaultRealmRole()) {
                keycloak.assignRealmRole(kcUser.id(), properties.defaultRealmRole());
            }
            // Invite exactly when the account has no credentials yet: a fresh create, or a retry
            // after the first invite attempt failed. A genuinely pre-existing account (has a
            // password) is never sent a forced credential-reset invite.
            if (!keycloak.hasCredentials(kcUser.id())) {
                keycloak.issueTemporaryCredentials(kcUser.id());
            }
            // One transaction for the row and the audit record that explains it: separate commits
            // would let a crash leave an app_user no audit accounts for — and the idempotent retry
            // (alreadyProvisioned) could never write the missing record afterwards.
            transactionTemplate.executeWithoutResult(tx -> {
                saveLocalUser(kcUser.id(), request.email());
                auditLog.record("identity.user_provisioned", null, kcUser.id(), null, "email=" + request.email());
            });
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
