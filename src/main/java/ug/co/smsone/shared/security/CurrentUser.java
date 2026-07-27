package ug.co.smsone.shared.security;

import java.util.Set;

/**
 * Authenticated-user abstraction decoupling controllers/services from JWT internals. Inject as a
 * controller method parameter (resolved by {@link CurrentUserArgumentResolver}) or obtain via
 * {@link CurrentUserProvider} in services.
 */
public record CurrentUser(String subject, String username, String email, Set<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
