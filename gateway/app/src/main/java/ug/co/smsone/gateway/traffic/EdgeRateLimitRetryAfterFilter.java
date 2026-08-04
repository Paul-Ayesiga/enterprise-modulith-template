package ug.co.smsone.gateway.traffic;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The edge burst limiter (SCG {@code RedisRateLimiter}) commits its 429 with the
 * {@code X-Edge-RateLimit-*} headers but no {@code Retry-After}, so a caller can't tell how long to
 * back off — unlike the modulith's plan-quota 429, which carries one. This stamps a {@code Retry-After}
 * onto an edge denial (a 429 that carries the edge headers but no Retry-After the backend would have
 * set), computed from the bucket's replenish rate, so both 429s tell the client what to do next. A
 * plan-quota 429 already has Retry-After, so it is left untouched.
 */
@Component
class EdgeRateLimitRetryAfterFilter implements GlobalFilter, Ordered {

    private final String retryAfterSeconds;

    EdgeRateLimitRetryAfterFilter(TrafficConfig.TrafficProperties properties) {
        TrafficConfig.TrafficProperties.RateLimit rate = properties.rateLimit();
        long seconds = Math.max(1L,
                (long) Math.ceil((double) rate.requestedTokens() / Math.max(1, rate.replenishRate())));
        this.retryAfterSeconds = String.valueOf(seconds);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            if (exchange.getResponse().getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                    && headers.containsHeader("X-Edge-RateLimit-Remaining")
                    && !headers.containsHeader(HttpHeaders.RETRY_AFTER)) {
                headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 6; // registers the hook before the late route rate-limiter commits
    }
}
