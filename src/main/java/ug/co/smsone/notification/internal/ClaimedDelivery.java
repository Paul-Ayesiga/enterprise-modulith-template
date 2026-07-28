package ug.co.smsone.notification.internal;

import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.notification.NotificationChannel;

/**
 * A delivery row claimed by a worker (attempts already incremented by the claim). {@code attempts}
 * doubles as the fencing token for status updates: a claim that went stale and was re-claimed has a
 * higher count, so the late original owner's updates no longer match. {@code channel} is null when
 * the stored value no longer maps to the enum (the queue dead-letters such rows instead of letting
 * one poison every claim batch). {@code throttledSince} is set while the row is continuously
 * deferred by its channel's provider rate limit.
 */
record ClaimedDelivery(UUID id, NotificationChannel channel, String recipient, String subject,
        String body, int attempts, int maxAttempts, Instant createdAt, Instant throttledSince) {
}
