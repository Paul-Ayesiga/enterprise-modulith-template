package ug.co.smsone.notification.internal;

import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.notification.NotificationChannelSender;
import ug.co.smsone.notification.NotificationMessage;

/**
 * Slack delivery via an incoming webhook: HTTP POST {@code {"text": ...}}. The recipient address is
 * the webhook URL; when blank, falls back to {@code app.notification.slack-webhook-url}.
 */
@Component
class SlackChannelSender implements NotificationChannelSender {

    private final NotificationProperties properties;

    SlackChannelSender(NotificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SLACK;
    }

    @Override
    public void send(NotificationMessage message) {
        String url = (message.address() != null && !message.address().isBlank())
                ? message.address()
                : properties.slackWebhookUrl();
        if (url == null || url.isBlank()) {
            throw new NotificationDeliveryException("No Slack webhook URL (recipient address or app.notification.slack-webhook-url)");
        }
        // Same SSRF guard as the webhook channel — the recipient address here is equally
        // caller-supplied; only the configured fallback URL is operator-trusted, and guarding it
        // too is harmless (Slack's endpoints are public).
        SafeHttpTargets.requireSafe(url, properties.webhookAllowPrivateHosts());
        String text = message.subject()
                + (message.body() == null || message.body().isBlank() ? "" : "\n" + message.body());
        String json = "{\"text\":" + HttpChannels.jsonString(text) + "}";
        HttpChannels.postJson(url, json, properties.webhookTimeoutSeconds());
    }
}
