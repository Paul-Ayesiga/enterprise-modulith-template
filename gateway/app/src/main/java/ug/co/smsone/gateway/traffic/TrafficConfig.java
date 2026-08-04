package ug.co.smsone.gateway.traffic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.security.EdgePrincipal;
import ug.co.smsone.gateway.core.web.GatewayAttributes;

/**
 * Traffic-management wiring. The rate limiter is a shared token bucket on Valkey (the same store the
 * platform already runs); its key is the caller — the resolved principal, else the tenant, else the
 * client IP — so a limit is per-consumer where a consumer is known and per-IP for anonymous traffic.
 * A route opts in through its {@code TrafficPolicy}; the rate here is the shared default.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrafficConfig.TrafficProperties.class)
class TrafficConfig {

    @Bean
    RedisRateLimiter edgeRateLimiter(TrafficProperties properties) {
        TrafficProperties.RateLimit limit = properties.rateLimit();
        RedisRateLimiter rateLimiter =
                new RedisRateLimiter(limit.replenishRate(), limit.burstCapacity(), limit.requestedTokens());
        // The EDGE burst limiter and the modulith's per-tenant PLAN quota both answer 429, but for
        // different reasons (burst-smoothing vs plan allowance). Give the edge its own header
        // namespace so both are fully visible and never collide: the standard X-RateLimit-* /
        // RateLimit stay the plan-quota signal a consumer reads, and these expose the edge bucket.
        rateLimiter.setRemainingHeader("X-Edge-RateLimit-Remaining");
        rateLimiter.setBurstCapacityHeader("X-Edge-RateLimit-Burst-Capacity");
        rateLimiter.setReplenishRateHeader("X-Edge-RateLimit-Replenish-Rate");
        rateLimiter.setRequestedTokensHeader("X-Edge-RateLimit-Requested-Tokens");
        return rateLimiter;
    }

    @Bean
    KeyResolver edgeKeyResolver() {
        return exchange -> {
            EdgePrincipal principal = GatewayAttributes.principal(exchange);
            if (principal != null && principal.subject() != null) {
                return Mono.just(principal.subject());
            }
            String tenant = GatewayAttributes.tenant(exchange);
            if (tenant != null) {
                return Mono.just("tenant:" + tenant);
            }
            var remote = exchange.getRequest().getRemoteAddress();
            return Mono.just("ip:" + (remote == null ? "unknown" : remote.getAddress().getHostAddress()));
        };
    }

    @ConfigurationProperties("gateway.traffic")
    record TrafficProperties(RateLimit rateLimit) {
        TrafficProperties {
            rateLimit = rateLimit == null ? new RateLimit(0, 0, 0) : rateLimit;
        }

        record RateLimit(int replenishRate, int burstCapacity, int requestedTokens) {
            RateLimit {
                if (replenishRate <= 0) {
                    replenishRate = 10;
                }
                if (burstCapacity <= 0) {
                    burstCapacity = 20;
                }
                if (requestedTokens <= 0) {
                    requestedTokens = 1;
                }
            }
        }
    }
}
