package ug.co.smsone.gateway.core.security;

import java.util.Set;

/**
 * A route's COARSE edge authorization policy — the whole of what the gateway checks (services keep
 * fine-grained {@code hasPermission}; ADR 0007 §8). {@code authenticated}: a valid token is required.
 * {@code requiredScopes}: the token must carry EVERY listed scope. {@code tenantPathTemplate}: when
 * set (e.g. {@code /api/v1/orgs/{tenant}/**}), the tenant extracted from the path must equal the
 * token's tenant, else 403. An {@link #OPEN} policy enforces nothing.
 */
public record AuthPolicy(boolean authenticated, Set<String> requiredScopes, String tenantPathTemplate) {

    public static final AuthPolicy OPEN = new AuthPolicy(false, Set.of(), null);

    public AuthPolicy {
        requiredScopes = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
    }

    public boolean enforcesTenant() {
        return tenantPathTemplate != null && !tenantPathTemplate.isBlank();
    }

    /** Whether a token is needed at all — required if authentication, scopes, or tenant are enforced. */
    public boolean requiresToken() {
        return authenticated || !requiredScopes.isEmpty() || enforcesTenant();
    }
}
