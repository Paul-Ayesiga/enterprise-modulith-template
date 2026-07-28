package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/** Published when a role's permission set changes (invalidates cached permissions for the org). */
public record RolePermissionsChanged(UUID orgId, UUID roleId, Instant occurredAt) {
}
