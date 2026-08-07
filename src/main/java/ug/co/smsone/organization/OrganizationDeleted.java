package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant was deleted by the platform (soft — the row and its trail remain restorable until the
 * retention purge). Published explicitly, since a repository delete fires no {@code @DomainEvents}.
 * {@code orgId} is {@code organization.id}.
 *
 * <p>The provider organization is deliberately NOT removed: with the local tenant row gone every
 * permission resolution fails closed anyway, and keeping the IdP record preserves both the account
 * linkage an un-delete needs and the {@code external_organization} row that resolves its tokens.
 */
public record OrganizationDeleted(UUID orgId, Instant occurredAt) {
}
