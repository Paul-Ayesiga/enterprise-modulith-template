package ug.co.smsone.gateway.core.traffic;

/**
 * A route's traffic-management policy — how the edge protects the backend behind it. All fields are
 * opt-in: a {@link #NONE} policy shapes nothing. {@code responseTimeoutMs} fails a slow backend fast
 * (504); {@code maxRequestBytes} rejects an oversized body (413); {@code rateLimited} applies the
 * shared token-bucket limiter (429); {@code circuitBreaker} trips on repeated backend failure (503).
 */
public record TrafficPolicy(Long responseTimeoutMs, Long maxRequestBytes, boolean rateLimited,
        boolean circuitBreaker) {

    public static final TrafficPolicy NONE = new TrafficPolicy(null, null, false, false);

    public boolean hasTimeout() {
        return responseTimeoutMs != null && responseTimeoutMs > 0;
    }

    public boolean hasMaxRequestBytes() {
        return maxRequestBytes != null && maxRequestBytes > 0;
    }
}
