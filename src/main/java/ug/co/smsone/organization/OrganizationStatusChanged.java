package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an organization is suspended or reactivated ({@code orgId} = {@code organization.id};
 * {@code status} is {@code ACTIVE} or {@code SUSPENDED}). Suspension takes effect immediately —
 * the permission cache is evicted on this event.
 */
public record OrganizationStatusChanged(UUID orgId, String status, Instant occurredAt) {
}
