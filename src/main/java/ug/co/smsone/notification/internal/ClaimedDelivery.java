package ug.co.smsone.notification.internal;

import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.notification.NotificationChannel;

/** A delivery row claimed by a worker (attempts already incremented by the claim). */
record ClaimedDelivery(UUID id, NotificationChannel channel, String recipient, String subject,
        String body, int attempts, int maxAttempts, Instant createdAt) {
}
