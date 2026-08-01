package ug.co.smsone.shared.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Two-level cache tuning. L1 = in-process Caffeine (short TTL, bounded), L2 = Valkey/Redis
 * (longer TTL, shared across instances). L2 can be disabled (tests, single-node dev); the
 * {@code l2-enabled} flag itself is read by {@code @ConditionalOnProperty} on {@code CacheConfig}'s
 * three Redis beans, so it is deliberately not a component here.
 */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(
        @DefaultValue("PT60S") Duration l1Ttl,
        @DefaultValue("10000") long l1MaxSize,
        @DefaultValue("PT10M") Duration l2Ttl) {
}
