package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant was deleted by the platform (soft — the row and its trail remain restorable until the
 * retention purge). Published explicitly, since a repository delete fires no {@code @DomainEvents}.
 * The Keycloak organization is deliberately NOT removed: with the local projection gone every
 * permission resolution fails closed, and keeping the IdP record preserves the account linkage an
 * un-delete needs.
 */
public record OrganizationDeleted(UUID orgId, Instant occurredAt) {
}
