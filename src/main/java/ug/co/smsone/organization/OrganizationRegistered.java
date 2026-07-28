package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/** Published when an organization is projected/created locally ({@code orgId} = Keycloak org id). */
public record OrganizationRegistered(UUID orgId, String alias, Instant occurredAt) {
}
