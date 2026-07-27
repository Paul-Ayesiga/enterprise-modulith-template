package ug.co.smsone.notification;

import java.util.Objects;

/**
 * A single delivery target: the {@link NotificationChannel} plus its channel-specific address —
 * an email address, phone number, in-app user id, or webhook/Slack URL.
 */
public record Recipient(NotificationChannel channel, String address) {

    public Recipient {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(address, "address");
    }

    public static Recipient email(String address) {
        return new Recipient(NotificationChannel.EMAIL, address);
    }

    public static Recipient sms(String phoneNumber) {
        return new Recipient(NotificationChannel.SMS, phoneNumber);
    }

    public static Recipient inApp(String userId) {
        return new Recipient(NotificationChannel.IN_APP, userId);
    }

    public static Recipient slack(String webhookUrl) {
        return new Recipient(NotificationChannel.SLACK, webhookUrl);
    }

    public static Recipient webhook(String url) {
        return new Recipient(NotificationChannel.WEBHOOK, url);
    }
}
