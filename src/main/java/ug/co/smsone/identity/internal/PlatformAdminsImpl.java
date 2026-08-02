package ug.co.smsone.identity.internal;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.security.PlatformAdmins;

/**
 * {@link PlatformAdmins} over Keycloak. Counts the DIRECT holders of {@code platform-superadmin} — the
 * data lives in the IdP, not the app DB (roles ride the token, never a local table).
 */
@Component
class PlatformAdminsImpl implements PlatformAdmins {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminsImpl.class);
    static final String SUPER_ADMIN_ROLE = "platform-superadmin";

    private final KeycloakUserAdminGateway keycloak;

    PlatformAdminsImpl(KeycloakUserAdminGateway keycloak) {
        this.keycloak = keycloak;
    }

    @Override
    public boolean isSoleSuperAdmin(String subject) {
        try {
            Set<String> holders = keycloak.usersWithRealmRole(SUPER_ADMIN_ROLE);
            return holders.size() == 1 && holders.contains(subject);
        } catch (RuntimeException ex) {
            // Fail OPEN: a data-subject right (erasure) must not hinge on Keycloak availability — the
            // bootstrap re-seeds a super-admin on the next startup if this ever lets the last one go.
            log.warn("Could not count platform super-admins to guard erasure of {}: {}", subject, ex.toString());
            return false;
        }
    }
}
