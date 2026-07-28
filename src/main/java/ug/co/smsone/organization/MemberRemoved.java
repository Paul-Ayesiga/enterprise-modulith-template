package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/** Published when a user's membership in an organization is removed. */
public record MemberRemoved(UUID orgId, String subject, Instant occurredAt) {
}
