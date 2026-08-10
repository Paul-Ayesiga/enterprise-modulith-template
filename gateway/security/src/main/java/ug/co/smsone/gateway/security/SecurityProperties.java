package ug.co.smsone.gateway.security;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Edge security configuration ({@code gateway.security.*}): where to fetch signing keys (the identity
 * provider's JWKS), the optional expected issuer, which JWT claims carry the tenant and the
 * organization, CORS, and the trusted internal-service tokens. The gateway validates JWTs against the
 * JWKS directly — no call to the platform.
 *
 * <p><b>{@code tenantClaim} and {@code organizationClaim} are two different questions</b> and merging
 * them is the mistake to avoid. {@code tenantClaim} is a flat string compared against a
 * {@code {tenant}} path variable, so a caller cannot address someone else's path segment; it is a
 * guard. {@code organizationClaim} is the IdP's own organization claim — Keycloak mints it as a MAP
 * keyed by alias — and it is what {@code CurrentUserProvider} resolves a tenant from on the platform
 * side. Only the second can decide which DEPLOYMENT serves the request, and a deployment that never
 * configured a flat {@code tenant} mapper would otherwise silently route every tenant to the shared
 * upstream while looking configured.
 */
@ConfigurationProperties("gateway.security")
public record SecurityProperties(String jwkSetUri, String issuer, String tenantClaim,
        String organizationClaim, Cors cors, List<InternalToken> internalTokens) {

    public SecurityProperties {
        tenantClaim = (tenantClaim == null || tenantClaim.isBlank()) ? "tenant" : tenantClaim;
        organizationClaim = (organizationClaim == null || organizationClaim.isBlank())
                ? "organization" : organizationClaim;
        cors = cors == null ? new Cors(null, null, null) : cors;
        internalTokens = internalTokens == null ? List.of() : internalTokens;
    }

    public record Cors(List<String> allowedOrigins, List<String> allowedMethods, List<String> allowedHeaders) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
            allowedMethods = allowedMethods == null || allowedMethods.isEmpty()
                    ? List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS") : allowedMethods;
            allowedHeaders = allowedHeaders == null || allowedHeaders.isEmpty() ? List.of("*") : allowedHeaders;
        }
    }

    /** A trusted service-to-service credential: a shared {@code token} → a named service + scopes. */
    public record InternalToken(String name, String token, Set<String> scopes) {
        public InternalToken {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }
}
