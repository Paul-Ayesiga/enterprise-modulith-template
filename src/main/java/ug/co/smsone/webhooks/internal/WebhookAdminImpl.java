package ug.co.smsone.webhooks.internal;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;
import ug.co.smsone.webhooks.WebhookAdmin;

/**
 * The {@link WebhookAdmin} port: thin mappings over {@link WebhookSubscriptionService}. The mask
 * matches the REST one — a constant describing the plaintext the tenant holds, revealing nothing of
 * the row's ciphertext.
 */
@Component
class WebhookAdminImpl implements WebhookAdmin {

    private static final String MASK = "whsec_••••••";

    private final WebhookSubscriptionService service;
    private final Clock clock;

    WebhookAdminImpl(WebhookSubscriptionService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public WindowedResult<SubscriptionView> list(UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(service.list(orgId, page), page, WebhookAdminImpl::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionView get(UUID orgId, UUID subscriptionId) {
        return toView(service.require(orgId, subscriptionId));
    }

    @Override
    public CreatedSubscription create(UUID orgId, String url, Set<String> events) {
        WebhookSubscriptionService.CreatedSubscription created = service.create(orgId, url, events);
        return new CreatedSubscription(toView(created.subscription()), created.plainSecret());
    }

    @Override
    public SubscriptionView update(UUID orgId, UUID subscriptionId, String url, Set<String> events,
            String status) {
        return toView(service.update(orgId, subscriptionId, url, events, parseStatus(status)));
    }

    @Override
    public CreatedSubscription rotateSecret(UUID orgId, UUID subscriptionId) {
        WebhookSubscriptionService.CreatedSubscription rotated = service.rotateSecret(orgId, subscriptionId);
        return new CreatedSubscription(toView(rotated.subscription()), rotated.plainSecret());
    }

    @Override
    public void delete(UUID orgId, UUID subscriptionId) {
        service.delete(orgId, subscriptionId);
    }

    @Override
    @Transactional(readOnly = true)
    public WindowedResult<DeliveryView> deliveries(UUID orgId, UUID subscriptionId, CursorPageRequest page) {
        return WindowedResult.of(service.deliveries(orgId, subscriptionId, page), page,
                delivery -> new DeliveryView(delivery.getId(), delivery.getEventType(),
                        delivery.getStatus(), delivery.getAttempts(), delivery.getMaxAttempts(),
                        delivery.getResponseStatus(), delivery.getLastError(), delivery.getCreatedAt(),
                        delivery.getDeliveredAt()));
    }

    @Override
    public void redeliver(UUID orgId, UUID subscriptionId, UUID deliveryId) {
        service.redeliver(orgId, subscriptionId, deliveryId, clock.instant());
    }

    @Override
    public java.util.List<EventTypeView> eventTypes() {
        return java.util.Arrays.stream(WebhookEventType.values())
                .map(type -> new EventTypeView(type.code(), type.description()))
                .toList();
    }

    private static SubscriptionView toView(WebhookSubscription subscription) {
        return new SubscriptionView(subscription.getId(), subscription.getUrl(),
                subscription.eventTypeSet(), subscription.getStatus().name(), MASK,
                subscription.getCreatedAt());
    }

    private static SubscriptionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return SubscriptionStatus.ACTIVE;
        }
        try {
            return SubscriptionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("status must be ACTIVE or DISABLED.",
                    ApiSource.pointer("/status"));
        }
    }
}
