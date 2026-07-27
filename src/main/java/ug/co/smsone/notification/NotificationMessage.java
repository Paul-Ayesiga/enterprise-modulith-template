package ug.co.smsone.notification;

import java.util.Map;

/**
 * The concrete payload handed to a {@link NotificationChannelSender}: the resolved address for its
 * channel, the subject/body, and free-form metadata (e.g. template variables, links).
 */
public record NotificationMessage(String address, String subject, String body, Map<String, String> metadata) {

    public NotificationMessage {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
