package ug.co.smsone.shared.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.ratelimit.RateLimitProperties.Tier;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;
import ug.co.smsone.shared.web.RequestPaths;

/**
 * Edge rate-limit filter for {@code /api/**}. Runs after the security chain (so the principal/tenant
 * claim is available) and before the idempotency filter. Matches the request to a tier, consumes a
 * distributed token, and either passes it through (with {@code RateLimit} headers) or short-circuits
 * with {@code 429} + {@code Retry-After} + the unified envelope. Active only when
 * {@code app.rate-limit.enabled=true}.
 *
 * <p>Coarse pre-auth per-IP flood shielding is intentionally left to the ingress/gateway — this
 * filter enforces the identity-aware, per-tenant business quota.
 */
@Component
@Order(-1)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final DistributedRateLimiter limiter;
    private final RateLimitKeyResolver keyResolver;
    private final EnvelopeErrorWriter errorWriter;
    private final MeterRegistry meters;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitFilter(RateLimitProperties properties, DistributedRateLimiter limiter,
            RateLimitKeyResolver keyResolver, EnvelopeErrorWriter errorWriter, MeterRegistry meters) {
        this.properties = properties;
        this.limiter = limiter;
        this.keyResolver = keyResolver;
        this.errorWriter = errorWriter;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RequestPaths.of(request);
        return !(path.equals("/api") || path.startsWith("/api/")) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Tier tier = properties.tierFor(RequestPaths.of(request), request.getMethod(), pathMatcher);
        String key = keyResolver.resolve(request, tier);
        RateLimitVerdict verdict = limiter.tryConsume(key, tier.capacity(), tier.refillPeriod(), tier.failClosed());

        setRateLimitHeaders(response, tier, verdict);
        if (verdict.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        Counter.builder("smsone.ratelimit.denied")
                .description("Requests refused with 429, by tier")
                .tag("tier", tier.id())
                .register(meters)
                .increment();
        response.setHeader("Retry-After", String.valueOf(verdict.retryAfterSeconds()));
        errorWriter.write(request, response, ErrorCode.RATE_LIMITED,
                "Rate limit exceeded for '" + tier.id() + "'. Retry after " + verdict.retryAfterSeconds() + "s.",
                null);
    }

    private static void setRateLimitHeaders(HttpServletResponse response, Tier tier, RateLimitVerdict verdict) {
        long reset = verdict.allowed() ? verdict.windowSeconds() : verdict.retryAfterSeconds();
        // draft-ietf-httpapi-ratelimit-headers (structured-field form)
        response.setHeader("RateLimit-Policy", "\"" + tier.id() + "\";q=" + verdict.limit() + ";w=" + verdict.windowSeconds());
        response.setHeader("RateLimit", "\"" + tier.id() + "\";r=" + verdict.remaining() + ";t=" + reset);
        // legacy de-facto headers for clients/SDKs that still read them
        response.setHeader("X-RateLimit-Limit", String.valueOf(verdict.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(verdict.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(reset));
    }
}
