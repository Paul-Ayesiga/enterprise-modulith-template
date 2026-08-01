package ug.co.smsone.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.ratelimit.RateLimitProperties.Tier;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;

/**
 * Resolves the bucket key for a request: {@code <prefix>:<tier>:<scopeType>:<scopeValue>}. Scope
 * degrades gracefully — TENANT keys by the caller's active organization (the Keycloak
 * {@code organization} claim), falling back to a configured flat tenant claim, then the principal
 * ({@code sub}), then the client IP; PRINCIPAL falls back to IP for anonymous routes.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
class RateLimitKeyResolver {

    private final RateLimitProperties properties;
    private final CurrentUserProvider currentUserProvider;

    RateLimitKeyResolver(RateLimitProperties properties, CurrentUserProvider currentUserProvider) {
        this.properties = properties;
        this.currentUserProvider = currentUserProvider;
    }

    String resolve(HttpServletRequest request, Tier tier) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (tier.scope() == RateLimitScope.TENANT) {
            String tenant = tenantKey(authentication);
            if (tenant != null) {
                return key(tier, "tenant", tenant);
            }
        }
        if (tier.scope() == RateLimitScope.TENANT || tier.scope() == RateLimitScope.PRINCIPAL) {
            // The subject, not the display name — the key type has always said "sub" and now means it.
            // A username-keyed bucket is escapable (rename yourself for a fresh quota) and inheritable
            // (a recycled username lands in the previous holder's bucket). Falls through to IP when
            // there is no authenticated subject.
            String subject = currentUserProvider.currentSubject().orElse(null);
            if (subject != null) {
                return key(tier, "sub", subject);
            }
        }
        return key(tier, "ip", clientIp(request));
    }

    /**
     * The tenant identity for a TENANT-scoped tier: the active organization id (parsed from the
     * Keycloak {@code organization} claim — the canonical tenant), or a configured flat tenant claim
     * for non-org identity providers. {@code null} when neither is present (the caller then keys by sub).
     */
    private String tenantKey(Authentication authentication) {
        String org = currentUserProvider.currentUser()
                .map(CurrentUser::activeOrgId)
                .map(UUID::toString)
                .orElse(null);
        if (org != null) {
            return org;
        }
        Jwt jwt = jwt(authentication);
        if (jwt != null) {
            String flat = jwt.getClaimAsString(properties.tenantClaim());
            if (flat != null && !flat.isBlank()) {
                return flat;
            }
        }
        return null;
    }

    private String key(Tier tier, String type, String value) {
        return properties.keyPrefix() + ":" + tier.id() + ":" + type + ":" + value;
    }

    private static Jwt jwt(Authentication authentication) {
        return (authentication != null && authentication.getPrincipal() instanceof Jwt token) ? token : null;
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
