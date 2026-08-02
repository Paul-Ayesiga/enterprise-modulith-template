package ug.co.smsone.gateway.core.traffic;

/**
 * A route's traffic-management policy — how the edge protects the backend behind it. All fields are
 * opt-in: a {@link #NONE} policy shapes nothing. {@code responseTimeoutMs} fails a slow backend fast
 * (504); {@code maxRequestBytes} rejects an oversized body (413); {@code rateLimited} applies the
 * shared token-bucket limiter (429); {@code circuitBreaker} trips on repeated backend failure (503
 * via a fallback); {@code retries} re-attempts a failed idempotent (GET) call up to N times;
 * {@code cacheTtlSeconds} caches a GET response at the edge for that many seconds (per caller tenant).
 */
public record TrafficPolicy(Long responseTimeoutMs, Long maxRequestBytes, boolean rateLimited,
        boolean circuitBreaker, int retries, Long cacheTtlSeconds) {

    public static final TrafficPolicy NONE = new TrafficPolicy(null, null, false, false, 0, null);

    public boolean hasTimeout() {
        return responseTimeoutMs != null && responseTimeoutMs > 0;
    }

    public boolean hasMaxRequestBytes() {
        return maxRequestBytes != null && maxRequestBytes > 0;
    }

    public boolean hasCache() {
        return cacheTtlSeconds != null && cacheTtlSeconds > 0;
    }
}
