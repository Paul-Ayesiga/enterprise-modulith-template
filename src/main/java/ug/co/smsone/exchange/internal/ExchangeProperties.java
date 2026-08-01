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
        Duration presignTtl) {

    ExchangeProperties {
        if (batchSize == null || batchSize <= 0) {
            batchSize = 500; // one progress commit (and one cancel check) per this many records
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            pollInterval = Duration.ofSeconds(2);
        }
        if (staleLock == null || staleLock.isZero() || staleLock.isNegative()) {
            // Also the heartbeat deadline: progress() re-stamps locked_at every batch, so a healthy
            // job outlives this however long the file is — only a CRASHED claimant goes stale.
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
    }
}
