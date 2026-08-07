package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/** Published when a member's role is reassigned (invalidates their cached permissions). */
public record MembershipRoleChanged(UUID orgId, UUID personId, Instant occurredAt) {
}
