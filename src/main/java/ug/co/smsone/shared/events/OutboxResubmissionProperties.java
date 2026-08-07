package ug.co.smsone.shared.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pacing for {@link OutboxResubmissionJob}. The two numbers are one policy, not two knobs: how much
 * of the incomplete backlog a pass may take, and how long a publication it just took is left alone
 * afterwards.
 *
 * <p>Read them together. At the shipped cron (every five minutes) a backoff of {@code retryBackoff}
 * covers {@code retryBackoff / 5min × maxPerRun} publications before the head of the backlog becomes
 * eligible again — 6000 at the defaults. Inside that figure the sweep drains strictly forward and no
 * publication is starved; beyond it a permanently-failing head does come back round and competes
 * with the tail. Raising {@code retryBackoff} widens the drain, at the cost of retrying a transient
 * failure less often. That is the whole trade-off, and it is why the defaults are stated as a pair.
 */
@ConfigurationProperties(prefix = "app.events.resubmission")
record OutboxResubmissionProperties(Boolean enabled, Integer maxPerRun, Duration retryBackoff) {

    OutboxResubmissionProperties {
        enabled = enabled == null || enabled;
        maxPerRun = maxPerRun == null ? 500 : maxPerRun;
        retryBackoff = retryBackoff == null ? Duration.ofHours(1) : retryBackoff;
        // Both are startup-fatal rather than silently coerced: a non-positive cap is a sweep that
        // retries nothing (the outbox stops being at-least-once and nobody finds out until an event
        // is missing), and a negative backoff puts the cutoff in the future, so every publication
        // reads as "just retried" and is skipped forever. Same failure either way, no log line.
        if (maxPerRun <= 0) {
            throw new IllegalArgumentException("app.events.resubmission.max-per-run must be positive");
        }
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("app.events.resubmission.retry-backoff must not be negative");
        }
    }
}
