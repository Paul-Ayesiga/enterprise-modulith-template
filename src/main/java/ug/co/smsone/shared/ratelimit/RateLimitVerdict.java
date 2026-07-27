package ug.co.smsone.shared.ratelimit;

/**
 * Outcome of a rate-limit check, carrying enough to build the {@code RateLimit}/{@code Retry-After}
 * response headers.
 *
 * @param allowed           whether the request may proceed
 * @param limit             the tier capacity (quota)
 * @param remaining         tokens left after this request
 * @param retryAfterSeconds seconds to wait before retrying (0 when allowed)
 * @param windowSeconds     the refill window in seconds
 */
public record RateLimitVerdict(boolean allowed, long limit, long remaining,
        long retryAfterSeconds, long windowSeconds) {

    static RateLimitVerdict allowed(long limit, long remaining, long windowSeconds) {
        return new RateLimitVerdict(true, limit, remaining, 0, windowSeconds);
    }

    static RateLimitVerdict denied(long limit, long retryAfterSeconds, long windowSeconds) {
        return new RateLimitVerdict(false, limit, 0, retryAfterSeconds, windowSeconds);
    }
}
