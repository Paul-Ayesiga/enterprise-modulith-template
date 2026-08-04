package ug.co.smsone.gateway.blocklist;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Dynamic abuse detection (fail2ban at the edge). Runs at +4 — just after the blocklist filter (+3,
 * so an already-blocked source short-circuits before it and never accrues more strikes) and before
 * auth (+10). It watches the OUTCOME of each request: a denied response of a counted status
 * ({@code 401/403} by default) is a strike, counted per source IP in a Valkey fixed window (shared,
 * so an attacker spread across replicas is still caught). When a source crosses the threshold it is
 * handed to {@link AutoBlockStore} for a TTL'd block, and the blocklist filter refuses it from the
 * next request on.
 *
 * <p>Strikes for a THROWN denial (auth's 401/403) are recorded before that response is written, so
 * the block trips deterministically; a status merely set downstream (a 429) is counted best-effort
 * on completion. Allowlisted sources never accrue strikes.
 */
@Component
@ConditionalOnProperty(prefix = "gateway.security.blocklist.auto", name = "enabled", havingValue = "true")
class AbuseGuardFilter implements GlobalFilter, Ordered {

    private static final String KEY_PREFIX = "gwabuse:";

    private final ReactiveStringRedisTemplate redis;
    private final EdgeClientIp clientIp;
    private final IpBlocklist blocklist;
    private final AutoBlockStore autoBlock;
    private final List<Integer> countedStatuses;
    private final int threshold;
    private final Duration window;

    AbuseGuardFilter(ReactiveStringRedisTemplate redis, EdgeClientIp clientIp, IpBlocklist blocklist,
            AutoBlockStore autoBlock, BlocklistProperties properties) {
        this.redis = redis;
        this.clientIp = clientIp;
        this.blocklist = blocklist;
        this.autoBlock = autoBlock;
        this.countedStatuses = properties.auto().statuses();
        this.threshold = properties.auto().threshold();
        this.window = properties.auto().window();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String ip = clientIp.resolve(exchange);
        if (ip == null || blocklist.isAllowed(ip)) {
            return chain.filter(exchange); // unknown source or trusted infra: never counted
        }
        return chain.filter(exchange)
                // A thrown denial (auth 401/403): count the strike, then re-raise so the response is
                // unchanged. Recording completes before the error handler writes, so the block is live
                // for the next request.
                .onErrorResume(ResponseStatusException.class,
                        error -> record(ip, error.getStatusCode().value()).then(Mono.error(error)))
                // A status set without throwing (e.g. a 429): count best-effort on completion.
                .then(Mono.defer(() -> {
                    HttpStatusCode status = exchange.getResponse().getStatusCode();
                    return status == null ? Mono.empty() : record(ip, status.value());
                }));
    }

    private Mono<Void> record(String ip, int status) {
        if (!countedStatuses.contains(status) || autoBlock.isBlocked(ip)) {
            return Mono.empty();
        }
        String key = KEY_PREFIX + ip;
        return redis.opsForValue().increment(key).flatMap(count -> {
            Mono<Void> windowStart = count == 1 ? redis.expire(key, window).then() : Mono.empty();
            if (count >= threshold) {
                // Reset the counter so a still-probing source does not re-block every request; the
                // blocklist filter is what refuses it now.
                return windowStart
                        .then(autoBlock.block(ip, "abuse threshold " + threshold + "/" + window))
                        .then(redis.delete(key).then());
            }
            return windowStart;
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 4; // after blocklist(+3), before lifecycle(+5)/auth(+10)
    }
}
