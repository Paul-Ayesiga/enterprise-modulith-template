package ug.co.smsone.notification.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Notification configuration. {@code admins} are the operators notified by
 * {@link ug.co.smsone.notification.Notifications#notifyAdmins}; each is emailed and gets an in-app
 * message. {@code slackWebhookUrl} is the default Slack target when a recipient omits its own URL.
 */
@ConfigurationProperties(prefix = "app.notification")
public record NotificationProperties(
        String from,
        List<Admin> admins,
        String slackWebhookUrl,
        int webhookTimeoutSeconds) {

    public NotificationProperties {
        if (from == null || from.isBlank()) {
            from = "no-reply@smsone.co.ug";
        }
        admins = admins == null ? List.of() : List.copyOf(admins);
        if (webhookTimeoutSeconds <= 0) {
            webhookTimeoutSeconds = 5;
        }
    }

    /** An administrator: a Keycloak username (in-app target) and an email address. */
    public record Admin(String username, String email) {
    }
}
