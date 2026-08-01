package ug.co.smsone.support;

import java.time.Instant;
import java.util.UUID;

/**
 * A ticket breached its SLA and was escalated. Published explicitly by the escalation job; the
 * webhooks module fans it out as {@code org.ticket.escalated} and the notification module tells
 * the support admins. Carries the new (bumped) priority.
 */
public record TicketEscalated(UUID ticketId, UUID orgId, String priority, Instant occurredAt) {
}
