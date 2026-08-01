package ug.co.smsone.webhooks.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Delivery tuning for the webhook worker. */
@ConfigurationProperties(prefix = "app.webhooks")
record WebhookProperties(
        Boolean workerAutoStart,
        Integer batchSize,
        Duration pollInterval,
        Duration staleLock,
        Integer maxAttempts,
        Duration retryBaseBackoff,
        Duration retryMaxBackoff,
        Integer timeoutSeconds,
        boolean allowPrivateHosts,
        Duration retention,
        String secretEncryptionKey) {

    WebhookProperties {
        if (workerAutoStart == null) {
            workerAutoStart = Boolean.TRUE; // absent => on; only an explicit false (tests) disables it
        }
        if (batchSize == null || batchSize <= 0) {
            batchSize = 50;
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            pollInterval = Duration.ofSeconds(5);
        }
        if (staleLock == null || staleLock.isZero() || staleLock.isNegative()) {
            staleLock = Duration.ofMinutes(2);
        }
        if (maxAttempts == null || maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (retryBaseBackoff == null || retryBaseBackoff.isZero() || retryBaseBackoff.isNegative()) {
            retryBaseBackoff = Duration.ofSeconds(10);
        }
        if (retryMaxBackoff == null || retryMaxBackoff.isZero() || retryMaxBackoff.isNegative()) {
            retryMaxBackoff = Duration.ofHours(1);
        }
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = 5;
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            retention = Duration.ofDays(30); // a zero/negative window would purge the whole delivery log
        }
        if (secretEncryptionKey == null || secretEncryptionKey.isBlank()) {
            // The yaml default supplies a dev key; reaching here means the property was explicitly
            // blanked, and silently signing with an empty-derived key would be worse than failing.
            throw new IllegalStateException(
                    "app.webhooks.secret-encryption-key must not be blank (set WEBHOOKS_SECRET_KEY)");
        }
    }
}
