package ug.co.smsone.webhooks.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.events.EventInbox;

/**
 * Fans a single event out to every active subscription in the org that wants it, enqueuing one durable
 * delivery each. Idempotent via {@link EventInbox}: an at-least-once redelivery of the same event does
 * not re-fan-out (the messageId is the event's business identity, which the caller supplies).
 */
@Component
class WebhookDispatcher {

    private static final String LISTENER_ID = "webhooks";

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryQueue queue;
    private final WebhookProperties properties;
    private final EventInbox inbox;

    WebhookDispatcher(WebhookSubscriptionRepository subscriptions, WebhookDeliveryQueue queue,
            WebhookProperties properties, EventInbox inbox) {
        this.subscriptions = subscriptions;
        this.queue = queue;
        this.properties = properties;
        this.inbox = inbox;
    }

    void dispatch(String messageId, UUID orgId, String eventCode, WebhookPayload payload) {
        if (!inbox.recordIfNew(LISTENER_ID, messageId)) {
            return; // already fanned out
        }
        String json = payload.toJson(); // one serialization — every matching subscription gets the same bytes
        List<NewWebhookDelivery> deliveries = subscriptions.findByOrgIdAndStatus(orgId, SubscriptionStatus.ACTIVE)
                .stream()
                .filter(subscription -> subscription.subscribesTo(eventCode))
                .map(subscription -> new NewWebhookDelivery(subscription.getId(), orgId, eventCode, json))
                .toList();
        if (!deliveries.isEmpty()) {
            queue.enqueue(deliveries, properties.maxAttempts());
        }
    }

    /**
     * Gives the dedup claim back when the fan-out it was taken for did not happen — the failure half of
     * {@link #dispatch}'s first line, and one that only became necessary with ADR 0011.
     *
     * <p>While the inbox row and the deliveries commit together, a failed fan-out un-claims itself by
     * rolling back and this is a no-op that matches zero rows. For a tenant on ANOTHER database they
     * cannot commit together ({@code EventInbox}'s class note), so the claim survives a failed fan-out
     * and would swallow the redelivery that is supposed to repair it — a webhook silently never sent,
     * which is exactly the outcome {@code WebhookEventListener}'s transaction exists to prevent. Calling
     * this unconditionally on failure is correct in both topologies, which is why the caller does not
     * branch on which one it is in.
     */
    void unclaim(String messageId) {
        inbox.forget(LISTENER_ID, messageId);
    }
}
