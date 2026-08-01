package ug.co.smsone.shared.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Idempotency-store policy (ADR 0005). Validated at startup, not at 4am: a non-positive retention
 * would purge every completed response (replays silently gone), and a non-positive lease would let
 * any duplicate take over a live claim.
 */
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(Duration inProgressLease, Integer maxBodyBytes, Duration retention) {

    public IdempotencyProperties {
        inProgressLease = inProgressLease == null ? Duration.ofMinutes(5) : inProgressLease;
        maxBodyBytes = maxBodyBytes == null ? 256 * 1024 : maxBodyBytes;
        retention = retention == null ? Duration.ofDays(1) : retention;
        if (inProgressLease.isZero() || inProgressLease.isNegative()) {
            throw new IllegalArgumentException("app.idempotency.in-progress-lease must be positive");
        }
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("app.idempotency.max-body-bytes must be positive");
        }
        // Zero would be "no replay window" — odd but coherent; negative puts the cutoff in the
        // future and purges every key including in-progress claims, so that one fails at startup.
        if (retention.isNegative()) {
            throw new IllegalArgumentException("app.idempotency.retention must not be negative");
        }
    }
}
