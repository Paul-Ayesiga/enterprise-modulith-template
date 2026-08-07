package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an organization is created ({@code orgId} = {@code organization.id}, the tenant key).
 * It used to carry the Keycloak organization id, and every listener stored that — which is how a
 * provider identifier became a Kill Bill account external key and the gateway's usage consumer id.
 */
public record OrganizationRegistered(UUID orgId, String alias, Instant occurredAt) {
}
