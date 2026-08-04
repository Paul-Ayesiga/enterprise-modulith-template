package ug.co.smsone.gateway.usage;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.quota.ConsumerResolver;

/**
 * Counts every request that has a resolvable consumer (the same identity quotas meter against) —
 * including ones a later stage refuses, because the platform did the work of answering them.
 * Pure in-memory increment on the hot path; shipping happens on the flusher's clock.
 */
@Component
class UsageMeteringFilter implements GlobalFilter, Ordered {

    private final ConsumerResolver consumerResolver;
    private final UsageBuffer buffer;

    UsageMeteringFilter(ConsumerResolver consumerResolver, UsageBuffer buffer) {
        this.consumerResolver = consumerResolver;
        this.buffer = buffer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return consumerResolver.resolve(exchange)
                .doOnNext(buffer::increment)
                .then(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 14; // right after auth (+10), before quota denies (+15)
    }
}
