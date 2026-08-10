package ug.co.smsone.shared.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.AntPathMatcher;
import ug.co.smsone.shared.deployment.DeploymentIdentity;

/**
 * Rate-limit configuration. Requests to {@code /api/**} are matched against {@code tiers} in order
 * (first match wins); unmatched requests fall through to {@code defaultTier}. Each tier maps 1:1 to
 * a named {@code RateLimit-Policy} in the response headers.
 *
 * <p>The {@code enabled} flag itself is read by {@code @ConditionalOnProperty} on {@code RateLimitConfig},
 * {@code RateLimitFilter} and {@code RateLimitKeyResolver}, so it is deliberately not a component here.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        String keyPrefix,
        String tenantClaim,
        Duration backendTimeout,
        boolean trustForwardedFor,
        List<Tier> tiers,
        Tier defaultTier) {

    public RateLimitProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "rl";
        }
        keyPrefix = keyPrefix.trim();
        // ONE segment, and it may not be the deployment marker (ADR 0010 §6 hop 2→3). A non-platform
        // deployment's keys all begin `dep:<id>:`, and the platform's begin with this prefix — so the
        // two namespaces are disjoint exactly while no platform key can start `dep:`. A prefix free to
        // spell its own separators could: `key-prefix: dep:acme` would put the platform's buckets in
        // the deployment `acme`'s namespace, silently, and one tenant's quota would be two tenants'.
        if (keyPrefix.contains(":") || DeploymentIdentity.MARKER.equals(keyPrefix)) {
            throw new IllegalArgumentException("app.rate-limit.key-prefix '" + keyPrefix + "' must be a"
                    + " single segment: no ':' and never '" + DeploymentIdentity.MARKER + "', which is"
                    + " the reserved first segment of an extracted deployment's Valkey namespace. A"
                    + " prefix that can spell that segment can put this installation's rate-limit"
                    + " buckets inside another deployment's namespace (ADR 0010 §6 hop 2->3).");
        }
        if (tenantClaim == null || tenantClaim.isBlank()) {
            tenantClaim = "tenant";
        }
        if (backendTimeout == null || backendTimeout.isZero() || backendTimeout.isNegative()) {
            backendTimeout = Duration.ofMillis(250); // tight, so a slow Valkey fails fast -> fail-open
        }
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
        if (defaultTier == null) {
            defaultTier = new Tier("default", "/**", List.of(), RateLimitScope.PRINCIPAL,
                    300, Duration.ofMinutes(1), false);
        }
    }

    /** Resolve the tier that governs a request (first matching, else the default). */
    public Tier tierFor(String path, String method, AntPathMatcher matcher) {
        for (Tier tier : tiers) {
            if (tier.matches(path, method, matcher)) {
                return tier;
            }
        }
        return defaultTier;
    }

    public record Tier(
            String id,
            String pathPattern,
            List<String> methods,
            RateLimitScope scope,
            long capacity,
            Duration refillPeriod,
            boolean failClosed) {

        public Tier {
            if (id == null || id.isBlank()) {
                id = "default";
            }
            // Keep ids to a token charset so they serialize safely into the RateLimit structured-field headers.
            id = id.replaceAll("[^A-Za-z0-9._-]", "_");
            if (pathPattern == null || pathPattern.isBlank()) {
                pathPattern = "/**";
            }
            methods = methods == null ? List.of()
                    : methods.stream().map(m -> m.toUpperCase(Locale.ROOT)).toList();
            if (scope == null) {
                scope = RateLimitScope.PRINCIPAL;
            }
            if (capacity <= 0) {
                capacity = 100;
            }
            if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
                refillPeriod = Duration.ofMinutes(1);
            }
        }

        boolean matches(String path, String method, AntPathMatcher matcher) {
            return matcher.match(pathPattern, path) && (methods.isEmpty() || methods.contains(method));
        }

        long windowSeconds() {
            return Math.max(1, refillPeriod.toSeconds());
        }
    }
}
