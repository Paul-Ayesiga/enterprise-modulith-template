package ug.co.smsone.webhooks.internal;

import java.util.UUID;

/** A claimed delivery, joined with its subscription's target + signing secret at claim time. */
record ClaimedWebhookDelivery(UUID id, String url, String secret, String eventType, String payload,
        int attempts, int maxAttempts) {
}
