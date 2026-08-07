package ug.co.smsone.notification;

import java.util.Objects;
import java.util.UUID;

/**
 * A single delivery target: the {@link NotificationChannel} plus its channel-specific address —
 * an email address, phone number, {@code person.id}, or webhook/Slack URL.
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

    /**
     * In-app target: the recipient's {@code person.id}. The only channel whose address is an identity
     * rather than a place — there is nowhere to deliver an in-app notification but this platform's own
     * UI, so the address IS the person. It is rendered into {@link #address()} because the four other
     * channels genuinely address a place and the record must hold all five; the string is parsed back
     * to a {@code UUID} by the in-app sender, which is the only reader that may.
     */
    public static Recipient inApp(UUID personId) {
        return new Recipient(NotificationChannel.IN_APP, Objects.requireNonNull(personId, "personId").toString());
    }

    public static Recipient slack(String webhookUrl) {
        return new Recipient(NotificationChannel.SLACK, webhookUrl);
    }

    public static Recipient webhook(String url) {
        return new Recipient(NotificationChannel.WEBHOOK, url);
    }
}
