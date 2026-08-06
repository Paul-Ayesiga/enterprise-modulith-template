package ug.co.smsone.webhooks.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model over a {@code webhook_delivery} row — backs the delivery-log REST. The queue itself is
 * driven with plain JDBC (see {@link WebhookDeliveryQueue}); this entity is never written through JPA,
 * so it carries no {@code @Version}. {@code payload} is deliberately not mapped (the log shows outcome,
 * not the body).
 */
@Entity
@Table(name = "webhook_delivery")
class WebhookDelivery {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "event_type")
    private String eventType;

    @Column
    private String status;

    @Column
    private int attempts;

    @Column(name = "max_attempts")
    private int maxAttempts;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected WebhookDelivery() {
        // JPA
    }

    UUID getId() {
        return id;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    UUID getOrgId() {
        return orgId;
    }

    Instant getDeliveredAt() {
        return deliveredAt;
    }

    String getEventType() {
        return eventType;
    }

    String getStatus() {
        return status;
    }

    int getAttempts() {
        return attempts;
    }

    int getMaxAttempts() {
        return maxAttempts;
    }

    Integer getResponseStatus() {
        return responseStatus;
    }

    String getLastError() {
        return lastError;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
