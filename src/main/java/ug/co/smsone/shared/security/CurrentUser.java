package ug.co.smsone.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * Authenticated-user abstraction decoupling controllers/services from JWT internals. Inject as a
 * controller method parameter (resolved by {@link CurrentUserArgumentResolver}) or obtain via
 * {@link CurrentUserProvider} in services.
 *
 * <p>{@code activeOrg*} come from the token's Keycloak {@code organization} claim — the tenant the
 * request is scoped to. They are {@code null} when the token targets zero or more than one org
 * (org-scoped {@code hasPermission} checks then deny until the client requests a single-org scope).
 */
public record CurrentUser(String subject, String username, String email, Set<String> roles,
        String activeOrgAlias, UUID activeOrgId) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasActiveOrg() {
        return activeOrgId != null;
    }
}
