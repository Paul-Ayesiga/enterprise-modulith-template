package ug.co.smsone.exchange.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Worker and job tuning. Normalized like the sibling queue properties. */
@ConfigurationProperties(prefix = "app.exchange")
record ExchangeProperties(
        Integer batchSize,
        Duration pollInterval,
        Duration staleLock,
        Integer maxAttempts,
        Boolean workerAutoStart,
        Duration presignTtl,
        Duration retryBaseBackoff,
        Duration retryMaxBackoff,
        Boolean scheduleFireEnabled,
        Duration retention) {

    private static final int MAX_BACKOFF_SHIFT = 16;

    /** Exponential per-claim backoff: base × 2^(attempts-1), capped. Zero base = immediate (tests). */
    Duration retryBackoff(int attempts) {
        long base = retryBaseBackoff.toMillis();
        long scaled = base << Math.min(Math.max(attempts - 1, 0), MAX_BACKOFF_SHIFT);
        return Duration.ofMillis(Math.min(scaled, retryMaxBackoff.toMillis()));
    }

    ExchangeProperties {
        if (batchSize == null || batchSize <= 0) {
            batchSize = 500; // one progress commit (and one cancel check) per this many records
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            pollInterval = Duration.ofSeconds(2);
        }
        if (staleLock == null || staleLock.isZero() || staleLock.isNegative()) {
            // Also the heartbeat deadline: the lock is re-stamped every progress commit AND
            // mid-batch (every ~staleLock/3 of wall time), so a healthy claimant only goes stale
            // if ONE record's remote work outlives this whole window — the documented ceiling.
            staleLock = Duration.ofMinutes(5);
        }
        if (maxAttempts == null || maxAttempts <= 0) {
            maxAttempts = 3; // claim generations, not record retries — a poison batch dies loudly
        }
        if (workerAutoStart == null) {
            workerAutoStart = Boolean.TRUE; // absent => on; only an explicit false (tests) disables it
        }
        if (presignTtl == null || presignTtl.isZero() || presignTtl.isNegative()) {
            presignTtl = Duration.ofMinutes(10);
        }
        if (retryBaseBackoff == null || retryBaseBackoff.isNegative()) {
            retryBaseBackoff = Duration.ofSeconds(30); // ZERO stays legal — tests retry immediately
        }
        if (retryMaxBackoff == null || retryMaxBackoff.isZero() || retryMaxBackoff.isNegative()) {
            retryMaxBackoff = Duration.ofMinutes(10);
        }
        if (scheduleFireEnabled == null) {
            scheduleFireEnabled = Boolean.TRUE; // absent => on; only an explicit false (tests) disables
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            retention = Duration.ofDays(30); // a zero/negative window would purge the whole job log
        }
    }
}
