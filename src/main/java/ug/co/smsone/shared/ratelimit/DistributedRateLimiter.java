package ug.co.smsone.shared.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.deployment.DeploymentIdentity;

/**
 * Distributed token-bucket limiter over Valkey (Bucket4j) — shared by the edge filter and the
 * notification egress limiter. Fail-open by default: if the backend is unavailable the request is
 * allowed (a limiter must never be a single point of failure); callers may opt into fail-closed.
 * When rate limiting is disabled (no client bean) every check allows.
 *
 * <p><b>Every bucket key is namespaced by the deployment here</b> (ADR 0010 §6 hop 2→3), and here
 * rather than in the two callers that build keys, because this is the only class in the application
 * that addresses a Bucket4j bucket at all. A caller cannot forget an axis it never applies: the edge
 * filter's {@code RateLimitKeyResolver} composes {@code <prefix>:<tier>:<scope>:<value>} and
 * {@code ChannelRateLimiter} composes {@code notif:rate:<channel>}, and both arrive here to be given
 * the deployment. Two deployments sharing a Valkey would otherwise share one tenant's quota — the
 * second deployment silently halving the throughput the first one sold.
 *
 * <p>Resilience: the connection is opened lazily with a tight timeout so neither startup nor an
 * outage blocks anything. After any backend error a short "don't retry" window keeps the outage from
 * turning into a per-request connect storm, and a {@link ReentrantLock} (not {@code synchronized})
 * guards lazy init so a blocking connect never pins a virtual thread.
 *
 * <p>Note: Bucket4j applies a {@link BucketConfiguration} only when a key is first created, so a
 * capacity change takes effect for an active key at most {@link #BUCKET_TTL} later.
 */
@Component
public class DistributedRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimiter.class);
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);
    private static final long BACKOFF_NANOS = Duration.ofSeconds(2).toNanos();

    private final ObjectProvider<RedisClient> clientProvider;
    private final DeploymentIdentity deployment;
    private final ReentrantLock initLock = new ReentrantLock();

    private volatile StatefulRedisConnection<String, byte[]> connection;
    private volatile LettuceBasedProxyManager<String> proxyManager;
    // Compared by subtraction (nanoTime() - retryNotBefore < 0), never by '<' directly: nanoTime has
    // an arbitrary — possibly negative — origin, and only differences between readings are meaningful.
    private volatile long retryNotBefore;
    private volatile boolean disabledLogged;
    private final ConcurrentHashMap<ConfigKey, Supplier<BucketConfiguration>> configurations =
            new ConcurrentHashMap<>();

    public DistributedRateLimiter(ObjectProvider<RedisClient> clientProvider, DeploymentIdentity deployment) {
        this.clientProvider = clientProvider;
        this.deployment = deployment;
    }

    public RateLimitVerdict tryConsume(String key, long capacity, Duration refillPeriod, boolean failClosed) {
        String bucketKey = deployment.valkeyKey(key);
        long window = Math.max(1, refillPeriod.toSeconds());
        RedisClient client = clientProvider.getIfAvailable();
        if (client == null) {
            logDisabledOnce();
            return RateLimitVerdict.allowed(capacity, capacity, window); // rate limiting disabled -> allow
        }
        if (System.nanoTime() - retryNotBefore < 0) {
            return failOpen(capacity, window, failClosed); // recent backend error -> fail fast, don't touch Valkey
        }
        LettuceBasedProxyManager<String> manager = proxyManager(client);
        if (manager == null) {
            return failOpen(capacity, window, failClosed);
        }
        try {
            BucketProxy bucket = manager.builder().build(bucketKey, configuration(capacity, refillPeriod));
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                return RateLimitVerdict.allowed(capacity, Math.max(0, probe.getRemainingTokens()), window);
            }
            long retryAfter = (probe.getNanosToWaitForRefill() / 1_000_000_000L) + 1;
            return RateLimitVerdict.denied(capacity, retryAfter, window);
        } catch (RuntimeException ex) {
            retryNotBefore = System.nanoTime() + BACKOFF_NANOS;
            log.warn("Rate-limit backend error for '{}' (fail-{}): {}", redact(bucketKey), failClosed ? "closed" : "open", ex.toString());
            return failOpen(capacity, window, failClosed);
        }
    }

    /** Best-effort return of one token — used to refund a send that never reached its provider. */
    public void addToken(String key, long capacity, Duration refillPeriod) {
        // The SAME namespacing as tryConsume, and it has to be: a refund addressed to an un-namespaced
        // key would credit a bucket nobody debits, leaving the real one permanently one token short.
        String bucketKey = deployment.valkeyKey(key);
        RedisClient client = clientProvider.getIfAvailable();
        if (client == null || System.nanoTime() - retryNotBefore < 0) {
            return;
        }
        LettuceBasedProxyManager<String> manager = proxyManager(client);
        if (manager == null) {
            return;
        }
        try {
            manager.builder().build(bucketKey, configuration(capacity, refillPeriod)).addTokens(1); // capped at capacity
        } catch (RuntimeException ex) {
            retryNotBefore = System.nanoTime() + BACKOFF_NANOS;
        }
    }

    private static RateLimitVerdict failOpen(long capacity, long window, boolean failClosed) {
        return failClosed
                ? RateLimitVerdict.denied(capacity, window, window)
                : RateLimitVerdict.allowed(capacity, capacity, window);
    }

    /** Lazily connect + build the proxy manager. Returns {@code null} (fail-open) only if connect fails. */
    private LettuceBasedProxyManager<String> proxyManager(RedisClient client) {
        LettuceBasedProxyManager<String> cached = proxyManager;
        if (cached != null) {
            return cached;
        }
        // Block on a ReentrantLock (virtual-thread friendly — no carrier pinning, unlike `synchronized`)
        // during the one-time connect, so a concurrent cold-start burst all shares the connection rather
        // than half of them failing open. Connect is bounded by the client's tight connect timeout.
        initLock.lock();
        try {
            if (proxyManager != null) {
                return proxyManager;
            }
            if (System.nanoTime() - retryNotBefore < 0) {
                return null; // a very recent connect attempt failed — don't retry-storm, fail open
            }
            StatefulRedisConnection<String, byte[]> conn =
                    client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
            try {
                LettuceBasedProxyManager<String> manager = Bucket4jLettuce.casBasedBuilder(conn)
                        .expirationAfterWrite(ExpirationAfterWriteStrategy
                                .basedOnTimeForRefillingBucketUpToMax(BUCKET_TTL))
                        .build();
                this.connection = conn;
                this.proxyManager = manager;
                return manager;
            } catch (RuntimeException ex) {
                conn.close(); // never orphan the Netty channel if bucket setup fails
                throw ex;
            }
        } catch (RuntimeException ex) {
            retryNotBefore = System.nanoTime() + BACKOFF_NANOS;
            log.warn("Rate-limit backend connect failed; failing open ~{}s: {}", BACKOFF_NANOS / 1_000_000_000L, ex.toString());
            return null;
        } finally {
            initLock.unlock();
        }
    }

    private void logDisabledOnce() {
        if (!disabledLogged) {
            disabledLogged = true;
            log.warn("Rate limiting is disabled (no Valkey client) — edge AND per-channel egress limits are NOT enforced");
        }
    }

    /** Drop the scope value (tenant/sub/IP) from a key before logging, keeping only prefix:tier. */
    private static String redact(String key) {
        int last = key.lastIndexOf(':');
        return last > 0 ? key.substring(0, last) : key;
    }

    /**
     * Memoized per tier: the tier set is small and static, and this sits on every {@code /api/**}
     * request — no reason to rebuild a Bandwidth + configuration + supplier per call.
     */
    private Supplier<BucketConfiguration> configuration(long capacity, Duration refillPeriod) {
        return configurations.computeIfAbsent(new ConfigKey(capacity, refillPeriod), key -> {
            BucketConfiguration config = BucketConfiguration.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(key.capacity())
                            .refillGreedy(key.capacity(), key.refillPeriod())
                            .build())
                    .build();
            return () -> config;
        });
    }

    private record ConfigKey(long capacity, Duration refillPeriod) {
    }

    @PreDestroy
    void closeConnection() {
        StatefulRedisConnection<String, byte[]> conn = this.connection;
        if (conn != null) {
            conn.close();
        }
    }
}
