package ug.co.smsone.webhooks.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ug.co.smsone.shared.persistence.AggregateRoot;

/** A tenant's outbound endpoint: where to POST, what to send, and the secret to sign with. */
@Entity
@Table(name = "webhook_subscription")
class WebhookSubscription extends AggregateRoot {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false, length = 200)
    private String secret;

    @Column(name = "event_types", nullable = false, columnDefinition = "text")
    private String eventTypes; // comma-joined codes

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    protected WebhookSubscription() {
        // JPA
    }

    static WebhookSubscription create(UUID orgId, String url, String secret, Set<String> eventTypes) {
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.orgId = orgId;
        subscription.url = url;
        subscription.secret = secret;
        subscription.eventTypes = join(eventTypes);
        subscription.status = SubscriptionStatus.ACTIVE;
        return subscription;
    }

    void update(String url, Set<String> eventTypes, SubscriptionStatus status) {
        this.url = url;
        this.eventTypes = join(eventTypes);
        this.status = status;
    }

    boolean subscribesTo(String eventCode) {
        return status == SubscriptionStatus.ACTIVE && eventTypeSet().contains(eventCode);
    }

    Set<String> eventTypeSet() {
        if (eventTypes == null || eventTypes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(eventTypes.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String join(Set<String> eventTypes) {
        return String.join(",", eventTypes);
    }

    UUID getOrgId() {
        return orgId;
    }

    String getUrl() {
        return url;
    }

    String getSecret() {
        return secret;
    }

    SubscriptionStatus getStatus() {
        return status;
    }
}
