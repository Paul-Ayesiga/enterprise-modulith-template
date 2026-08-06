package ug.co.smsone.gateway.quota;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.quota.ConsumerResolver;
import ug.co.smsone.gateway.core.quota.Quota;
import ug.co.smsone.gateway.core.quota.QuotaProvider;

/**
 * Enforces each consumer's plan quota at the edge (distinct from the Phase-3 token bucket: this is the
 * subscription's ceiling over a longer window). Resolve the consumer, look up its {@link Quota} from the
 * plan (via the {@link QuotaProvider} port), and count calls in a fixed window on Valkey — over the
 * ceiling is 429. Off when no {@code QuotaProvider} is on the context; a request with no resolvable
 * consumer or an unlimited quota passes without ever touching Valkey.
 */
@Component
class QuotaFilter implements GlobalFilter, Ordered {

    private static final String KEY_PREFIX = "gwquota:";

    private final ConsumerResolver consumerResolver;
    private final QuotaProvider quotaProvider;
    private final ReactiveStringRedisTemplate redis;

    QuotaFilter(ConsumerResolver consumerResolver, ObjectProvider<QuotaProvider> quotaProvider,
            ReactiveStringRedisTemplate redis) {
        this.consumerResolver = consumerResolver;
        this.quotaProvider = quotaProvider.getIfAvailable();
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (quotaProvider == null) {
            return chain.filter(exchange); // quotas not configured
        }
        // Decide FIRST, then run the chain exactly once. chain.filter is a Mono<Void> — it always
        // completes EMPTY — so it must never sit upstream of an empty-fallback: a switchIfEmpty
        // there re-subscribed the entire chain onto the already-committed response, the route's
        // rate limiter fired a second time, and its late header write blew up mid-connection —
        // keep-alive clients saw the socket close under a half-framed body. Same lesson as
        // EdgeAuthorizationFilter's principal guard.
        return admission(exchange).then(Mono.defer(() -> chain.filter(exchange)));
    }

    /** Completes when the request may pass; errors 429 when the consumer is over its plan ceiling. */
    private Mono<Void> admission(ServerWebExchange exchange) {
        return consumerResolver.resolve(exchange)
                .flatMap(consumer -> quotaProvider.quotaFor(consumer)
                        .filter(Quota::isLimited)
                        .flatMap(quota -> enforce(consumer, quota)))
                .then(); // no consumer, no quota, or unlimited → nothing to enforce
    }

    private Mono<Void> enforce(String consumer, Quota quota) {
        String key = KEY_PREFIX + consumer;
        return redis.opsForValue().increment(key).flatMap(count -> {
            // Set the window TTL once, on the first call that opened the window.
            Mono<Void> windowStart = count == 1 ? redis.expire(key, quota.window()).then() : Mono.empty();
            if (count > quota.limit()) {
                return windowStart.then(Mono.error(
                        new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Quota exceeded")));
            }
            return windowStart;
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15; // after auth (+10), before plugins (+20) and routing
    }
}
