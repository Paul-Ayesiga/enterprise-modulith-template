package ug.co.smsone.gateway.core.security;

import java.util.Set;

/**
 * The identity the edge resolved from a request — a subject, its tenant (when present), its scopes,
 * and whether it is a TRUSTED internal service (which bypasses tenant enforcement). Deliberately
 * minimal and generic: where it came from (a Keycloak JWT, an API key, an internal service token) is
 * the authenticator's concern, not the pipeline's.
 */
public record EdgePrincipal(String subject, String tenant, Set<String> scopes, boolean internal) {

    public EdgePrincipal {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
