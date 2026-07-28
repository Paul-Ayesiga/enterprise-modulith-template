package ug.co.smsone.notification.internal;

import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.notification.NotificationChannelSender;
import ug.co.smsone.notification.NotificationMessage;

/**
 * Webhook delivery: HTTP POST {@code {"subject","body"}} to the recipient's URL. Recipient URLs are
 * caller-supplied data, so the {@link SafeHttpTargets} SSRF guard applies before every send;
 * {@code app.notification.webhook-allow-private-hosts=true} disables the address check for dev/test
 * rigs that legitimately post to localhost.
 */
@Component
class WebhookChannelSender implements NotificationChannelSender {

    private final NotificationProperties properties;

    WebhookChannelSender(NotificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WEBHOOK;
    }

    @Override
    public void send(NotificationMessage message) {
        SafeHttpTargets.requireSafe(message.address(), properties.webhookAllowPrivateHosts());
        String body = message.body() == null ? "" : message.body();
        String json = "{\"subject\":" + HttpChannels.jsonString(message.subject())
                + ",\"body\":" + HttpChannels.jsonString(body) + "}";
        HttpChannels.postJson(message.address(), json, properties.webhookTimeoutSeconds());
    }
}
