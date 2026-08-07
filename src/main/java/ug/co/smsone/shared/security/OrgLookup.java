package ug.co.smsone.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for turning a token's {@code organization} claim into an {@code organization.id}, implemented by
 * the module that owns {@code external_organization}. The org-side twin of {@link PersonLookup}, for the
 * same reason and with the same absent-implementation posture: no implementation means no active
 * organization, which means every org-scoped permission check denies.
 *
 * <p>This is what makes {@link CurrentUser#organizationId()} a tenant key of ours rather than Keycloak's.
 * The claim carries an alias and a provider-minted id; {@code kc_org_id} used to BE the tenant key, and
 * V11 records where that ended — the identifier escaped into every module, the public API, Kill Bill and
 * the gateway. Resolving it here, once, is the fix.
 */
public interface OrgLookup {

    /**
     * The organization this claim names, or empty when nothing here is linked to it.
     *
     * <p>Both identifiers from the claim are passed because Keycloak's {@code organization} claim is a
     * map KEYED BY ALIAS — {@code {"acme":{"id":"…"}}} — and either half can be the one that resolves:
     * the id is stable, the alias survives a claim that carries no id at all, and V11 indexes both
     * ({@code uq_external_organization_ext_live}, {@code uq_external_organization_alias_live}) precisely
     * so neither is a scan. Which one wins is the implementation's decision, not the edge's, so that a
     * cutover or a second provider changes rows over there rather than parsing here.
     *
     * <p>{@code externalOrgId} is a String and not a UUID on purpose: it holds whatever the provider
     * mints (a Google Workspace customer id is {@code C01abcdef}), and typing it to one provider's shape
     * is the coupling {@code external_organization} exists to remove. A malformed or absent id is simply
     * an id that resolves to nothing — never an error, and never a reason to reject the token.
     *
     * <p>An alias is NEVER trusted as an organization id. Resolution goes through the link table in both
     * directions; a caller that matched the alias string against {@code organization.alias} would let a
     * token scoped to one tenant address another whose local slug happens to collide.
     */
    Optional<UUID> organizationId(String issuer, String externalOrgId, String externalAlias);
}
