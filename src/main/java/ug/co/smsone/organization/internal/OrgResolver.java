package ug.co.smsone.organization.internal;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one seam where a provider organization id becomes an {@code organization.id}, and back — the
 * org-side twin of {@code identity.internal.PersonResolver}, and the only class in this module that
 * knows a provider mints tenant identifiers at all.
 *
 * <p><b>Everything above this class speaks {@code organization.id}.</b> Confining the translation here
 * is what makes "no Keycloak organization id escapes the module" checkable rather than aspirational.
 */
@Component
class OrgResolver {

    private final ExternalOrganizationRepository links;
    private final OrgResolutionCache cache;
    private final Clock clock;
    private final String keycloakIssuer;

    /**
     * The issuer is read from the resource-server property, not from a key of our own and not composed
     * from {@code app.keycloak-admin.base-url} + realm: {@code external_organization.issuer} has to be
     * byte-identical to the {@code iss} the edge accepted, or a perfectly valid token resolves to no
     * tenant and every member of every org loses all org-scoped access at once. Same argument, same
     * property, as {@code PersonResolver} — and they must not disagree.
     */
    OrgResolver(ExternalOrganizationRepository links, OrgResolutionCache cache, Clock clock,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String keycloakIssuer) {
        this.links = links;
        this.cache = cache;
        this.clock = clock;
        this.keycloakIssuer = keycloakIssuer;
    }

    /**
     * The tenant a token's {@code organization} claim names. The id is tried first — it is stable across
     * a rename, whereas the alias is the thing being renamed — and the alias is the fallback for the
     * claims that carry no id at all.
     *
     * <p>An unrecognised issuer resolves to nothing rather than being searched for: {@code provider} is
     * the leading column of both unique indexes, so a lookup without one is a scan, and guessing that
     * every issuer is Keycloak would silently match a second realm's org ids against this one's.
     *
     * <p><b>The id leg is cached, the alias leg is not</b>, and the asymmetry is deliberate:
     * {@link OrgResolutionCache} says why an immutable provider id may be remembered and a reusable
     * alias may not. A deployment whose claim carries the id — Keycloak's does — therefore pays the
     * edge nothing here in the steady state; one whose claim carries only an alias keeps paying one
     * indexed read per request, on purpose.
     */
    Optional<UUID> organizationIdOf(String issuer, String externalOrgId, String externalAlias) {
        OrgProvider provider = providerOf(issuer);
        if (provider == null) {
            return Optional.empty();
        }
        return byExternalId(provider, issuer, externalOrgId)
                .or(() -> byAlias(provider, issuer, externalAlias));
    }

    /** The same resolution for this deployment's own IdP — the internal callers' shorthand. */
    Optional<UUID> organizationIdOfKeycloakOrg(String externalOrgId) {
        return byExternalId(OrgProvider.KEYCLOAK, keycloakIssuer, externalOrgId);
    }

    /**
     * The reverse direction, needed only by the calls that talk TO the provider (its admin API knows
     * this tenant by its own id and nothing else). Empty when the tenant has no Keycloak link.
     */
    @Transactional(readOnly = true)
    Optional<String> keycloakOrgIdOf(UUID organizationId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        return links.findByOrganizationIdAndProviderAndIssuer(
                        organizationId, OrgProvider.KEYCLOAK, keycloakIssuer)
                .map(ExternalOrganization::getExternalOrgId);
    }

    /**
     * Idempotent link of a tenant to its Keycloak organization. Re-linking an already-linked tenant
     * only refreshes the alias — the id is immutable and the row is the tenant's identity over there,
     * so replacing it would strand every token still carrying the old one.
     *
     * <p>Runs inside the caller's transaction (the projection write), which is also why there is no
     * lost-update race to guard: the {@code organization} row this links was created in that same
     * transaction, or found by an alias whose partial unique index already serialized the creators.
     */
    void linkKeycloakOrg(UUID organizationId, String externalOrgId, String alias) {
        links.findByOrganizationIdAndProviderAndIssuer(organizationId, OrgProvider.KEYCLOAK, keycloakIssuer)
                .ifPresentOrElse(
                        existing -> {
                            if (!java.util.Objects.equals(existing.getExternalAlias(), alias)) {
                                existing.realias(alias);
                                links.save(existing);
                            }
                        },
                        () -> links.save(ExternalOrganization.link(organizationId, OrgProvider.KEYCLOAK,
                                keycloakIssuer, externalOrgId, alias, clock.instant())));
    }

    /**
     * Which vocabulary word names this issuer. One entry today, and deliberately a lookup rather than a
     * branch on a provider argument the caller passes: deciding that KEYCLOAK-vs-GOOGLE names an issuer
     * is the code change "adding a provider is a row" exists to forbid, so the day there is a second
     * one this reads a table or a property and every caller stays untouched.
     */
    private OrgProvider providerOf(String issuer) {
        return keycloakIssuer.equals(issuer) ? OrgProvider.KEYCLOAK : null;
    }

    /** Cached. The guards and the trim run here so the cache key is the normalized triple. */
    private Optional<UUID> byExternalId(OrgProvider provider, String issuer, String externalOrgId) {
        if (externalOrgId == null || externalOrgId.isBlank()) {
            return Optional.empty();
        }
        // Text, then parsed here: see OrgResolutionCache on why a bare UUID cannot cross L2.
        String organizationId = cache.organizationIdTextOf(provider, issuer, externalOrgId.trim());
        return organizationId == null ? Optional.empty() : Optional.of(UUID.fromString(organizationId));
    }

    /** Uncached, deliberately — an alias is mutable and reusable. See {@link OrgResolutionCache}. */
    private Optional<UUID> byAlias(OrgProvider provider, String issuer, String externalAlias) {
        if (externalAlias == null || externalAlias.isBlank()) {
            return Optional.empty();
        }
        return links.findByProviderAndIssuerAndExternalAlias(provider, issuer, externalAlias.trim())
                .map(ExternalOrganization::getOrganizationId);
    }
}
