package ug.co.smsone.notification.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
        Delivery delivery) {

    public NotificationProperties {
        if (from == null || from.isBlank()) {
            from = "no-reply@smsone.co.ug";
        }
        admins = admins == null ? List.of() : List.copyOf(admins);
        if (webhookTimeoutSeconds <= 0) {
            webhookTimeoutSeconds = 5;
        }
        delivery = delivery == null ? Delivery.defaults() : delivery;
    }

    /** An administrator: a Keycloak username (in-app target) and an email address. */
    public record Admin(String username, String email) {
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
            Boolean workerAutoStart) {

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
        }

        static Delivery defaults() {
            return new Delivery(0, 0, 0, null, null, null, null, null, null, 0, null);
        }
    }
}
