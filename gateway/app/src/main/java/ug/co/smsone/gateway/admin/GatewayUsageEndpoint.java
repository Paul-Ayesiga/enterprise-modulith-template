package ug.co.smsone.gateway.admin;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.quota.Quota;
import ug.co.smsone.gateway.core.quota.QuotaProvider;

/**
 * Per-consumer usage — the observability face of the quota filter, on the management port. It reads
 * the SAME Valkey window counters the {@code QuotaFilter} enforces against (keys {@code gwquota:*}),
 * so what it reports is the live truth, not a parallel count: {@code used} is the attempts this window
 * (denied attempts included — they increment before the ceiling check), {@code limit}/{@code window}
 * come from the consumer's plan via the {@link QuotaProvider}, and {@code resetSeconds} is the
 * window's remaining TTL. The list is sorted by usage — top talkers first. A consumer absent from
 * Valkey simply hasn't called this window; the selector form still reports it (used 0) with its plan
 * ceiling, which is what a "my usage" page wants.
 */
@Component
@Endpoint(id = "gatewayusage")
public class GatewayUsageEndpoint {

    private static final String KEY_PREFIX = "gwquota:";
    private static final int SCAN_BATCH = 500;

    private final ReactiveStringRedisTemplate redis;
    private final QuotaProvider quotaProvider;

    public GatewayUsageEndpoint(ReactiveStringRedisTemplate redis, ObjectProvider<QuotaProvider> quotaProvider) {
        this.redis = redis;
        this.quotaProvider = quotaProvider.getIfAvailable();
    }

    @ReadOperation
    public Mono<List<Map<String, Object>>> usage() {
        return redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(SCAN_BATCH).build())
                .map(key -> key.substring(KEY_PREFIX.length()))
                .flatMap(this::row)
                .collectSortedList(Comparator.comparingLong(
                        (Map<String, Object> row) -> (Long) row.get("used")).reversed());
    }

    @ReadOperation
    public Mono<Map<String, Object>> consumerUsage(@Selector String consumer) {
        return row(consumer);
    }

    private Mono<Map<String, Object>> row(String consumer) {
        String key = KEY_PREFIX + consumer;
        Mono<Long> used = redis.opsForValue().get(key).map(Long::parseLong).defaultIfEmpty(0L);
        Mono<Duration> ttl = redis.getExpire(key).defaultIfEmpty(Duration.ZERO);
        Mono<Quota> quota = quotaProvider == null ? Mono.just(Quota.UNLIMITED)
                : quotaProvider.quotaFor(consumer).defaultIfEmpty(Quota.UNLIMITED);
        return Mono.zip(used, ttl, quota).map(parts -> {
            long usedCount = parts.getT1();
            long resetSeconds = Math.max(0, parts.getT2().getSeconds());
            Quota plan = parts.getT3();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("consumer", consumer);
            row.put("used", usedCount);
            row.put("limited", plan.isLimited());
            row.put("limit", plan.isLimited() ? plan.limit() : null);
            row.put("windowSeconds", plan.isLimited() ? plan.window().getSeconds() : null);
            row.put("remaining", plan.isLimited() ? Math.max(0, plan.limit() - usedCount) : null);
            row.put("resetSeconds", resetSeconds);
            return row;
        });
    }
}
