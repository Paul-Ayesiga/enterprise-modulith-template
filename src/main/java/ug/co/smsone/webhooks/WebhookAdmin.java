package ug.co.smsone.webhooks;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Webhook management port for other protocol surfaces (the MCP module today) — the same operations
 * the webhook REST controller performs, through the same service: SSRF-guarded URLs, the
 * subscriptions-max entitlement, secret-shown-once semantics and the delivery-log retention rules
 * all ride along.
 */
public interface WebhookAdmin {

    WindowedResult<SubscriptionView> list(UUID orgId, CursorPageRequest page);

    SubscriptionView get(UUID orgId, UUID subscriptionId);

    /** The signing secret is returned in full exactly ONCE — here. Every later read masks it. */
    CreatedSubscription create(UUID orgId, String url, Set<String> events);

    /** {@code url}/{@code events} replace wholesale; a null {@code status} resets to ACTIVE. */
    SubscriptionView update(UUID orgId, UUID subscriptionId, String url, Set<String> events, String status);

    /** Mints a replacement secret, returned in full exactly once; the old one stops verifying now. */
    CreatedSubscription rotateSecret(UUID orgId, UUID subscriptionId);

    void delete(UUID orgId, UUID subscriptionId);

    WindowedResult<DeliveryView> deliveries(UUID orgId, UUID subscriptionId, CursorPageRequest page);

    /** Re-queue one FAILED (dead-lettered) delivery for a fresh retry cycle. */
    void redeliver(UUID orgId, UUID subscriptionId, UUID deliveryId);

    /** The fixed catalog of subscribable event codes — what {@code create}/{@code update} accept. */
    java.util.List<EventTypeView> eventTypes();

    record EventTypeView(String code, String description) {
    }

    record SubscriptionView(UUID id, String url, Set<String> events, String status,
            String maskedSecret, Instant createdAt) {
    }

    record CreatedSubscription(SubscriptionView subscription, String plainSecret) {
    }

    record DeliveryView(UUID id, String eventType, String status, int attempts, int maxAttempts,
            Integer responseStatus, String lastError, Instant createdAt, Instant deliveredAt) {
    }
}
