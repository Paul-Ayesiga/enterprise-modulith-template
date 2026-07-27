package ug.co.smsone.notification;

import java.util.List;
import java.util.Map;

/**
 * A request to deliver one subject/body to a set of {@link Recipient recipients}, each carrying its
 * own channel. The dispatcher routes every recipient to the matching channel sender independently —
 * a failure on one recipient/channel never aborts the others.
 */
public record NotificationRequest(String subject, String body, List<Recipient> recipients, Map<String, String> metadata) {

    public NotificationRequest {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static NotificationRequest of(String subject, String body, List<Recipient> recipients) {
        return new NotificationRequest(subject, body, recipients, Map.of());
    }
}
