package ug.co.smsone.webhooks.internal;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.http.SafeOutboundUrl;
import ug.co.smsone.shared.http.UnsafeOutboundUrlException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Manages a tenant's webhook subscriptions and reads their delivery log.
 *
 * <p>Every mutation is audited. A subscription outlives the request that created it — it carries a
 * signing secret shown once and then keeps receiving the org's events — so "who pointed our events at
 * that endpoint" has to be answerable later. It is also the shape of change most worth attributing when
 * it was made from inside an impersonation session, where {@code created_by} records the target while
 * only {@code audit_log} names the operator behind them.
 */
@Service
class WebhookSubscriptionService {

    private static final Sort DELIVERY_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Sort SUBSCRIPTION_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookDeliveryQueue queue;
    private final WebhookProperties properties;
    private final AuditLog auditLog;

    WebhookSubscriptionService(WebhookSubscriptionRepository subscriptions,
            WebhookDeliveryRepository deliveries, WebhookDeliveryQueue queue, WebhookProperties properties,
            AuditLog auditLog) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.queue = queue;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    @Transactional
    WebhookSubscription create(UUID orgId, String url, Set<String> eventCodes) {
        requireSafeUrl(url);
        Set<String> events = requireKnownEvents(eventCodes);
        String secret = "whsec_" + randomHex();
        WebhookSubscription created = subscriptions.save(WebhookSubscription.create(orgId, url, secret, events));
        // The secret is never a state value here — the audit trail records where events go, not what
        // signs them.
        auditLog.record("webhooks.subscription_created", orgId, created.getId().toString(), null, state(created));
        return created;
    }

    @Transactional(readOnly = true)
    Window<WebhookSubscription> list(UUID orgId, CursorPageRequest page) {
        return subscriptions.findBy((root, query, cb) -> cb.equal(root.get("orgId"), orgId),
                q -> q.limit(page.size()).sortBy(SUBSCRIPTION_SORT).scroll(page.scrollPosition(SUBSCRIPTION_SORT)));
    }

    @Transactional(readOnly = true)
    WebhookSubscription require(UUID orgId, UUID id) {
        return subscriptions.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("Webhook subscription not found."));
    }

    @Transactional
    WebhookSubscription update(UUID orgId, UUID id, String url, Set<String> eventCodes, SubscriptionStatus status) {
        requireSafeUrl(url);
        Set<String> events = requireKnownEvents(eventCodes);
        WebhookSubscription subscription = require(orgId, id);
        String before = state(subscription);
        subscription.update(url, events, status);
        WebhookSubscription updated = subscriptions.save(subscription);
        auditLog.record("webhooks.subscription_updated", orgId, id.toString(), before, state(updated));
        return updated;
    }

    /**
     * Soft-deletes the subscription: it stops matching events and disappears from the API, but its
     * delivery log survives. The {@code on delete cascade} from {@code webhook_delivery} no longer
     * fires because the row is never actually removed — which is the point, since "what did we send
     * that endpoint?" is exactly the question asked after someone deletes it. {@link #deliveries}
     * therefore resolves the subscription WITHOUT the restriction, or the retained log would be
     * unreachable for its whole life and then cascade-purged with the row.
     *
     * <p>Cancelling the outstanding deliveries is the other half of the delete, and the half a soft
     * delete silently dropped: a DELETE is how a tenant revokes a compromised endpoint, so everything
     * already queued for it must stop too — not keep retrying for hours against the URL and secret
     * they just revoked. Same transaction as the delete: a revocation that half-applies is worse than
     * one that fails.
     */
    @Transactional
    void delete(UUID orgId, UUID id) {
        WebhookSubscription subscription = require(orgId, id);
        subscriptions.delete(subscription);
        queue.cancelOutstanding(id, "subscription deleted");
        // There is no deleted_by column on a soft-deletable row (@SQLDelete cannot see the security
        // context) — this row is where who revoked the endpoint is recorded.
        auditLog.record("webhooks.subscription_deleted", orgId, id.toString(), state(subscription), null);
    }

    /**
     * The delivery log for one subscription, newest first, cursor-paginated. Readable after the
     * subscription is deleted (until retention purges it) — see {@link #delete}; still tenant-scoped,
     * so another org's id is a 404 exactly as before.
     */
    Window<WebhookDelivery> deliveries(UUID orgId, UUID id, CursorPageRequest page) {
        if (!subscriptions.existsIncludingDeleted(id, orgId)) {
            throw new NotFoundException("Webhook subscription not found.");
        }
        return deliveries.findBy(
                (root, query, cb) -> cb.equal(root.get("subscriptionId"), id),
                q -> q.limit(page.size()).sortBy(DELIVERY_SORT).scroll(page.scrollPosition(DELIVERY_SORT)));
    }

    private void requireSafeUrl(String url) {
        try {
            SafeOutboundUrl.requireSafe(url, properties.allowPrivateHosts());
        } catch (UnsafeOutboundUrlException ex) {
            throw new ValidationException(ex.getMessage(), ApiSource.pointer("/data/attributes/url"));
        }
    }

    private static Set<String> requireKnownEvents(Set<String> eventCodes) {
        if (eventCodes == null || eventCodes.isEmpty()) {
            throw new ValidationException("At least one event type is required.",
                    ApiSource.pointer("/data/attributes/events"));
        }
        Set<String> unknown = new LinkedHashSet<>();
        for (String code : eventCodes) {
            if (!WebhookEventType.isValid(code)) {
                unknown.add(code);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ValidationException("Unknown event type(s): " + String.join(", ", unknown),
                    ApiSource.pointer("/data/attributes/events"));
        }
        return eventCodes;
    }

    /** What a reviewer needs from a subscription row: where it points, what it takes, whether it is live. */
    private static String state(WebhookSubscription subscription) {
        return subscription.getUrl() + " " + subscription.eventTypeSet() + " " + subscription.getStatus();
    }

    private static String randomHex() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
