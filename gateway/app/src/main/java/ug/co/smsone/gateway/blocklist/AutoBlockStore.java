package ug.co.smsone.gateway.blocklist;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.audit.AuditSink;
import ug.co.smsone.gateway.core.audit.EdgeAuditEvent;

/**
 * The dynamic half of the deny-list: abuse-driven blocks with a TTL, shared across gateway replicas.
 * The source of truth is a Valkey sorted set ({@code gwautoblock}, score = expiry epoch-ms), so a
 * source blocked by one instance is honored by all and a restart does not forget. Reads are pure
 * in-memory against a snapshot refreshed in bulk every few seconds — the hot path (every request at
 * the blocklist filter) never touches Valkey. The instance that trips a block updates its own
 * snapshot immediately; peers converge within the refresh interval, which is ample for a control
 * that sits behind the already-distributed rate limiter.
 *
 * <p>Only present when {@code gateway.security.blocklist.auto.enabled=true} — the whole subsystem is
 * off (and needs no Valkey) otherwise.
 */
@Component
@ConditionalOnProperty(prefix = "gateway.security.blocklist.auto", name = "enabled", havingValue = "true")
public class AutoBlockStore {

    private static final Logger securityLog = LoggerFactory.getLogger("gateway.security");
    private static final String ZKEY = "gwautoblock";
    private static final long REFRESH_MS = 5_000;

    private final ReactiveStringRedisTemplate redis;
    private final Duration blockDuration;
    private final MeterRegistry meterRegistry;
    private final AuditSink auditSink;

    /** ip → expiry epoch-ms. The read snapshot, converged from Valkey; lazily expired on read. */
    private final Map<String, Long> blocked = new ConcurrentHashMap<>();

    AutoBlockStore(ReactiveStringRedisTemplate redis, BlocklistProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry, ObjectProvider<AuditSink> auditSink) {
        this.redis = redis;
        this.blockDuration = properties.auto().blockDuration();
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.auditSink = auditSink.getIfAvailable();
    }

    /** Hot-path read: is this source currently auto-blocked? Pure in-memory. */
    public boolean isBlocked(String ip) {
        Long expiry = blocked.get(ip);
        if (expiry == null) {
            return false;
        }
        if (expiry <= System.currentTimeMillis()) {
            blocked.remove(ip, expiry);
            return false;
        }
        return true;
    }

    /** Reactive — called from the abuse guard's request pipeline when a threshold is crossed. */
    Mono<Void> block(String ip, String reason) {
        long expiry = System.currentTimeMillis() + blockDuration.toMillis();
        return redis.opsForZSet().add(ZKEY, ip, (double) expiry)
                .doOnSuccess(added -> {
                    blocked.put(ip, expiry); // immediate local effect on the detecting instance
                    if (meterRegistry != null) {
                        meterRegistry.counter("gateway.blocklist.autoblocked").increment();
                    }
                    securityLog.warn("edge_ip_autoblocked ip={} reason={} ttl={}", ip, reason, blockDuration);
                    if (auditSink != null) {
                        auditSink.publish(new EdgeAuditEvent("gateway.ip_autoblocked", null, null, null,
                                null, 403, reason + " ip=" + ip, null, null)).subscribe();
                    }
                })
                .then();
    }

    /** Lift an auto-block early (operator action from the admin endpoint). Local + Valkey. */
    public boolean unblock(String ip) {
        boolean wasLocal = blocked.remove(ip) != null;
        redis.opsForZSet().remove(ZKEY, ip).subscribe();
        return wasLocal;
    }

    /** ip → seconds-until-expiry, for the admin listing. From the local snapshot (converged). */
    public Map<String, Long> entries() {
        long now = System.currentTimeMillis();
        Map<String, Long> view = new LinkedHashMap<>();
        blocked.forEach((ip, expiry) -> {
            if (expiry > now) {
                view.put(ip, (expiry - now) / 1000);
            }
        });
        return view;
    }

    /**
     * Pull the live set from Valkey in bulk and prune the expired, so every instance converges on the
     * shared truth and stale rows leave both stores. Resilient by design — a Valkey blip logs and the
     * last snapshot stands rather than failing open or shut.
     */
    @Scheduled(fixedDelay = REFRESH_MS, initialDelay = REFRESH_MS)
    void refresh() {
        long now = System.currentTimeMillis();
        redis.opsForZSet().removeRangeByScore(ZKEY, Range.leftUnbounded(Range.Bound.inclusive((double) now)))
                .then(redis.opsForZSet()
                        .rangeByScoreWithScores(ZKEY, Range.rightUnbounded(Range.Bound.inclusive((double) now)))
                        .collectMap(tuple -> tuple.getValue(), tuple -> tuple.getScore().longValue()))
                .subscribe(live -> {
                    blocked.putAll(live);
                    blocked.keySet().removeIf(ip -> !live.containsKey(ip)); // dropped elsewhere / expired
                }, error -> securityLog.warn("auto-block refresh failed: {}", error.toString()));
    }
}
