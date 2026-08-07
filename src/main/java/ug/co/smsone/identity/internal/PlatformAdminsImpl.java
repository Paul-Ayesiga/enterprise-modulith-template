package ug.co.smsone.identity.internal;

import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.security.PlatformAdmins;

/**
 * {@link PlatformAdmins} over Keycloak. Counts the DIRECT holders of {@code platform-superadmin} — the
 * data lives in the IdP, not the app DB (roles ride the token, never a local table).
 *
 * <p>The port takes a {@code person.id} — its caller ({@code ComplianceService}, deciding whether an
 * erasure would remove the last super-admin) is reasoning about a human and should not have to hold a
 * provider's id to ask. The set being counted is nevertheless a set of Keycloak user ids, because
 * platform roles are Keycloak's, so the translation happens here through {@link PersonResolver}: this
 * module owns {@code external_identity}, and it is the only one that can still answer correctly the day
 * a second provider exists.
 */
@Component
class PlatformAdminsImpl implements PlatformAdmins {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminsImpl.class);
    static final String SUPER_ADMIN_ROLE = "platform-superadmin";

    private final KeycloakUserAdminGateway keycloak;
    private final PersonResolver resolver;

    PlatformAdminsImpl(KeycloakUserAdminGateway keycloak, PersonResolver resolver) {
        this.keycloak = keycloak;
        this.resolver = resolver;
    }

    @Override
    public boolean isSoleSuperAdmin(UUID personId) {
        String subject = resolver.keycloakSubjectOf(personId).orElse(null);
        if (subject == null) {
            // No Keycloak link means no realm roles, so this person cannot be holding the last one. The
            // same fail-toward-permitting shape the rest of this class has, reached without a call.
            return false;
        }
        try {
            Set<String> holders = keycloak.usersWithRealmRole(SUPER_ADMIN_ROLE);
            return holders.size() == 1 && holders.contains(subject);
        } catch (RuntimeException ex) {
            // Fail OPEN: a data-subject right (erasure) must not hinge on Keycloak availability — the
            // bootstrap re-seeds a super-admin on the next startup if this ever lets the last one go.
            log.warn("Could not count platform super-admins to guard erasure of {}: {}", personId, ex.toString());
            return false;
        }
    }
}
