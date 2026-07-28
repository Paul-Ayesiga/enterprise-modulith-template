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
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.http.SafeOutboundUrl;
import ug.co.smsone.shared.http.UnsafeOutboundUrlException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/** Manages a tenant's webhook subscriptions and reads their delivery log. */
@Service
class WebhookSubscriptionService {

    private static final Sort DELIVERY_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookProperties properties;

    WebhookSubscriptionService(WebhookSubscriptionRepository subscriptions,
            WebhookDeliveryRepository deliveries, WebhookProperties properties) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.properties = properties;
    }

    @Transactional
    WebhookSubscription create(UUID orgId, String url, Set<String> eventCodes) {
        requireSafeUrl(url);
        Set<String> events = requireKnownEvents(eventCodes);
        String secret = "whsec_" + randomHex();
        return subscriptions.save(WebhookSubscription.create(orgId, url, secret, events));
    }

    List<WebhookSubscription> list(UUID orgId) {
        return subscriptions.findByOrgId(orgId);
    }

    WebhookSubscription require(UUID orgId, UUID id) {
        return subscriptions.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("Webhook subscription not found."));
    }

    @Transactional
    WebhookSubscription update(UUID orgId, UUID id, String url, Set<String> eventCodes, SubscriptionStatus status) {
        requireSafeUrl(url);
        Set<String> events = requireKnownEvents(eventCodes);
        WebhookSubscription subscription = require(orgId, id);
        subscription.update(url, events, status);
        return subscriptions.save(subscription);
    }

    @Transactional
    void delete(UUID orgId, UUID id) {
        subscriptions.delete(require(orgId, id)); // cascades to its deliveries
    }

    /** The delivery log for one subscription, newest first, cursor-paginated. */
    Window<WebhookDelivery> deliveries(UUID orgId, UUID id, CursorPageRequest page) {
        require(orgId, id); // 404 if the subscription isn't the caller's org
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

    private static String randomHex() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
