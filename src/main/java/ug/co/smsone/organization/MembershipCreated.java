package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/** Published when a person is added to an organization with a role. */
public record MembershipCreated(UUID orgId, UUID personId, String roleCode, Instant occurredAt) {
}
