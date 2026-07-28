package ug.co.smsone.notification.internal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ug.co.smsone.notification.NotificationChannel;

/**
 * Notification configuration. {@code admins} are notified by
 * {@link ug.co.smsone.notification.Notifications#notifyAdmins} (email + in-app); {@code delivery}
 * tunes the async fan-out worker.
 */
@ConfigurationProperties(prefix = "app.notification")
public record NotificationProperties(
        String from,
        List<Admin> admins,
        String slackWebhookUrl,
        int webhookTimeoutSeconds,
        Boolean webhookAllowPrivateHosts,
        Delivery delivery) {

    public NotificationProperties {
        if (from == null || from.isBlank()) {
            from = "no-reply@smsone.co.ug";
        }
        admins = admins == null ? List.of() : List.copyOf(admins);
        if (webhookTimeoutSeconds <= 0) {
            webhookTimeoutSeconds = 5;
        }
        if (webhookAllowPrivateHosts == null) {
            webhookAllowPrivateHosts = Boolean.FALSE; // SSRF guard on by default; tests/dev opt out
        }
        delivery = delivery == null ? Delivery.defaults() : delivery;
    }

    /**
     * An administrator, identified by email. The in-app target (the immutable Keycloak subject) is
     * resolved from this email at dispatch time — config never carries mutable usernames.
     */
    public record Admin(String email) {
    }

    /** Fan-out worker tuning. */
    public record Delivery(
            int batchSize,
            int concurrency,
            int maxAttempts,
            Duration pollInterval,
            Duration retryBaseBackoff,
            Duration retryMaxBackoff,
            Duration staleLock,
            Duration retention,
            Duration purgeInterval,
            int maxDrainBatches,
            Boolean workerAutoStart,
            Duration throttleDelay,
            Duration throttleMaxAge,
            Map<NotificationChannel, ChannelRate> rate) {

        public Delivery {
            if (batchSize <= 0) {
                batchSize = 200;
            }
            if (concurrency <= 0) {
                concurrency = 16;
            }
            if (maxAttempts <= 0) {
                maxAttempts = 5;
            }
            if (pollInterval == null) {
                pollInterval = Duration.ofSeconds(1);
            }
            if (retryBaseBackoff == null) {
                retryBaseBackoff = Duration.ofSeconds(10);
            }
            if (retryMaxBackoff == null) {
                retryMaxBackoff = Duration.ofMinutes(10);
            }
            if (staleLock == null || staleLock.isZero() || staleLock.isNegative()) {
                staleLock = Duration.ofMinutes(5); // must be positive; a stale-lock of 0 reclaims every in-flight row
            }
            if (retention == null) {
                retention = Duration.ofDays(7);
            }
            if (purgeInterval == null) {
                purgeInterval = Duration.ofHours(1);
            }
            if (maxDrainBatches <= 0) {
                maxDrainBatches = 25;
            }
            if (workerAutoStart == null) {
                workerAutoStart = Boolean.TRUE; // absent => on; only an explicit false (tests) disables it
            }
            if (throttleDelay == null || throttleDelay.isNegative()) {
                throttleDelay = Duration.ofSeconds(1); // how long to defer a delivery throttled by its channel rate
            }
            if (throttleMaxAge == null || throttleMaxAge.isZero() || throttleMaxAge.isNegative()) {
                throttleMaxAge = Duration.ofHours(1); // dead-letter a delivery throttled longer than this (mis-set rate guard)
            }
            rate = rate == null ? Map.of() : Map.copyOf(rate);
        }

        static Delivery defaults() {
            return new Delivery(0, 0, 0, null, null, null, null, null, null, 0, null, null, null, null);
        }
    }

    /**
     * Outbound per-channel provider limit (cluster-wide via Valkey) — protects downstream providers
     * (SMS gateway, SMTP relay, …) from being overrun regardless of instance count.
     */
    public record ChannelRate(long capacity, Duration period) {
        public ChannelRate {
            if (capacity <= 0) {
                capacity = 1;
            }
            if (period == null || period.isZero() || period.isNegative()) {
                period = Duration.ofSeconds(1);
            }
        }
    }
}
