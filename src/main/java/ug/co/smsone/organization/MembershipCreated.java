package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/** Published when a user is added to an organization with a role. */
public record MembershipCreated(UUID orgId, String subject, String roleCode, Instant occurredAt) {
}
