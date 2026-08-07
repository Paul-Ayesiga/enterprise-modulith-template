package ug.co.smsone.identity;

import java.util.UUID;

/**
 * Attach or detach a person at the identity provider's own organization, so the tokens it issues them
 * carry the {@code organization} claim the edge resolves the tenant from.
 *
 * <p><b>Why this port exists at all.</b> Keycloak's organization-member endpoints take a Keycloak USER
 * id, and nothing below the edge may see one. The module that owns memberships knows people only as
 * {@code person.id}, so the call has to live in the module that owns {@code external_identity} — the one
 * place permitted to hold an identifier minted elsewhere, and the only one that can still translate
 * correctly the day a second provider exists. The same discipline keeps the provider's ORG id inside
 * {@code external_organization}.
 *
 * <p>{@code externalOrgId} is opaque and is passed through as the String it is: it is
 * {@code external_organization.external_org_id}, which is varchar precisely so a non-UUID identifier
 * (a Google customer id, {@code C01abcdef}) fits.
 *
 * <p>Both calls are idempotent and both are no-ops for a person with no link at that provider — whether
 * a person is federated there is this module's fact to know, not its caller's to check.
 */
public interface ProviderOrgMembership {

    void attach(UUID personId, String externalOrgId);

    void detach(UUID personId, String externalOrgId);
}
