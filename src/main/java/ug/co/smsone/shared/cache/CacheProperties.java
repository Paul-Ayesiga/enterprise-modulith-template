package ug.co.smsone.shared.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Two-level cache tuning. L1 = in-process Caffeine (short TTL, bounded), L2 = Valkey/Redis
 * (longer TTL, shared across instances). L2 can be disabled (tests, single-node dev); the
 * {@code l2-enabled} flag itself is read by {@code @ConditionalOnProperty} on {@code CacheConfig}'s
 * three Redis beans, so it is deliberately not a component here.
 *
 * <p><b>{@code l1MaxSize} is a tenancy knob now, not just a memory one (ADR 0010 §3.5).</b> A TENANT
 * cache prefixes the organization into its key, so the entry count of {@code org-permissions} and
 * {@code org-entitlements} is no longer "one per active member of the org you are looking at" but the
 * sum across every tenant this node serves — one L1 shared by all of them. Left at the old 10,000 the
 * change would have paid for itself in correctness and taken it straight back out of the hit rate,
 * silently, as a hot tenant evicted everyone else. Raised to 50,000 to keep roughly the same headroom
 * per tenant at the silo ceiling (ADR 0010 §8 Q1, 200) while staying small in bytes: the values are a
 * short {@code Set<String>} of permission codes and a {@code Map<String, Long>} of entitlements. The
 * alternative the ADR names — partitioning L1 per tenant — buys per-tenant fairness rather than
 * capacity and is a change to make with a measurement, not ahead of one.
 */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(
        @DefaultValue("PT60S") Duration l1Ttl,
        @DefaultValue("50000") long l1MaxSize,
        @DefaultValue("PT10M") Duration l2Ttl) {
}
