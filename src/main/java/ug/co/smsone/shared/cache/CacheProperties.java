package ug.co.smsone.shared.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Two-level cache tuning. L1 = in-process Caffeine (short TTL, bounded), L2 = Valkey/Redis
 * (longer TTL, shared across instances). L2 can be disabled (tests, single-node dev).
 */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(
        @DefaultValue("PT60S") Duration l1Ttl,
        @DefaultValue("10000") long l1MaxSize,
        @DefaultValue("PT10M") Duration l2Ttl,
        @DefaultValue("true") boolean l2Enabled) {
}
