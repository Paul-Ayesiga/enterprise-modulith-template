package ug.co.smsone.subscription;

import java.time.Instant;
import java.util.UUID;

/**
 * An organization's plan or status changed. Consumers: the entitlement cache evictor (a downgrade
 * must bite immediately) and the webhooks fan-out ({@code org.subscription_changed}).
 */
public record SubscriptionChanged(UUID orgId, String planCode, String status, Instant occurredAt) {
}
