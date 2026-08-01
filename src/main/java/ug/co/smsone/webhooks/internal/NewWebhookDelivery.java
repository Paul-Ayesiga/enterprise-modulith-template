package ug.co.smsone.webhooks.internal;

import java.util.UUID;

/** A delivery to enqueue: which subscription, which event, and the (already-serialized) JSON body. */
record NewWebhookDelivery(UUID subscriptionId, UUID orgId, String eventType, String payload) {
}
