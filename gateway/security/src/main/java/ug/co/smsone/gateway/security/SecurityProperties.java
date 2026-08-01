package ug.co.smsone.gateway.security;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Edge security configuration ({@code gateway.security.*}): where to fetch signing keys (the identity
 * provider's JWKS), the optional expected issuer, which JWT claim carries the tenant, CORS, and the
 * trusted internal-service tokens. The gateway validates JWTs against the JWKS directly — no call to
 * the platform.
 */
@ConfigurationProperties("gateway.security")
public record SecurityProperties(String jwkSetUri, String issuer, String tenantClaim, Cors cors,
        List<InternalToken> internalTokens) {

    public SecurityProperties {
        tenantClaim = (tenantClaim == null || tenantClaim.isBlank()) ? "tenant" : tenantClaim;
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
