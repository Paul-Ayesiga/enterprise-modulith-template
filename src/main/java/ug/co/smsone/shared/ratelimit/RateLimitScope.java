package ug.co.smsone.shared.ratelimit;

/**
 * What a tier's limit is keyed by. The resolver degrades gracefully: TENANT falls back to the
 * principal and then the client IP when a tenant claim is absent, PRINCIPAL falls back to IP.
 */
public enum RateLimitScope {
    IP,
    PRINCIPAL,
    TENANT
}
