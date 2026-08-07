package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/** Published when a person's membership in an organization is removed. */
public record MemberRemoved(UUID orgId, UUID personId, Instant occurredAt) {
}
