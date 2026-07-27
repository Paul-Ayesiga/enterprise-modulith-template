package ug.co.smsone.notification.internal;

import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.shared.ratelimit.DistributedRateLimiter;
import ug.co.smsone.shared.ratelimit.RateLimitVerdict;

/**
 * Outbound per-channel rate limiter: before a send, consumes a token from the channel's cluster-wide
 * (Valkey) bucket so downstream providers (SMS gateway, SMTP relay, …) are never overrun regardless
 * of how many app instances are fanning out. A channel with no configured rate is unlimited.
 */
@Component
class ChannelRateLimiter {

    private final DistributedRateLimiter limiter;
    private final NotificationProperties.Delivery config;

    ChannelRateLimiter(DistributedRateLimiter limiter, NotificationProperties properties) {
        this.limiter = limiter;
        this.config = properties.delivery();
    }

    /** @return empty if the send may proceed now, else how long to defer the delivery. */
    Optional<Duration> check(NotificationChannel channel) {
        NotificationProperties.ChannelRate rate = config.rate().get(channel);
        if (rate == null) {
            return Optional.empty(); // no per-channel limit configured
        }
        RateLimitVerdict verdict = limiter.tryConsume(key(channel), rate.capacity(), rate.period(), false);
        if (verdict.allowed()) {
            return Optional.empty();
        }
        long deferSeconds = Math.max(1, Math.max(config.throttleDelay().toSeconds(), verdict.retryAfterSeconds()));
        return Optional.of(Duration.ofSeconds(deferSeconds));
    }

    /** Return the token consumed by {@link #check} when the subsequent send failed. No-op if unlimited. */
    void refund(NotificationChannel channel) {
        NotificationProperties.ChannelRate rate = config.rate().get(channel);
        if (rate != null) {
            limiter.addToken(key(channel), rate.capacity(), rate.period());
        }
    }

    private static String key(NotificationChannel channel) {
        return "notif:rate:" + channel;
    }
}
