package ug.co.smsone.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.ratelimit.RateLimitProperties.Tier;

/**
 * Resolves the bucket key for a request: {@code <prefix>:<tier>:<scopeType>:<scopeValue>}. Scope
 * degrades gracefully — TENANT uses the configured tenant claim, falling back to the principal
 * ({@code sub}) then the client IP; PRINCIPAL falls back to IP for anonymous routes.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
class RateLimitKeyResolver {

    private final RateLimitProperties properties;

    RateLimitKeyResolver(RateLimitProperties properties) {
        this.properties = properties;
    }

    String resolve(HttpServletRequest request, Tier tier) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Jwt jwt = jwt(authentication);

        String type;
        String value;
        String tenant = (tier.scope() == RateLimitScope.TENANT && jwt != null)
                ? jwt.getClaimAsString(properties.tenantClaim())
                : null;
        if (tenant != null && !tenant.isBlank()) {
            type = "tenant";
            value = tenant;
        } else if ((tier.scope() == RateLimitScope.TENANT || tier.scope() == RateLimitScope.PRINCIPAL)
                && isAuthenticated(authentication)) {
            type = "sub";
            value = authentication.getName();
        } else {
            type = "ip";
            value = clientIp(request);
        }
        return properties.keyPrefix() + ":" + tier.id() + ":" + type + ":" + value;
    }

    private static Jwt jwt(Authentication authentication) {
        return (authentication != null && authentication.getPrincipal() instanceof Jwt token) ? token : null;
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null;
    }

    private static final int MAX_IP_LENGTH = 45; // longest IPv6 textual form

    private String clientIp(HttpServletRequest request) {
        if (properties.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // Trust only the RIGHTMOST hop — the one our own proxy appended; the leftmost entries
                // are client-supplied and spoofable (they would let a caller mint unbounded keys).
                String[] hops = forwarded.split(",");
                String candidate = hops[hops.length - 1].trim();
                if (!candidate.isEmpty() && candidate.length() <= MAX_IP_LENGTH) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
