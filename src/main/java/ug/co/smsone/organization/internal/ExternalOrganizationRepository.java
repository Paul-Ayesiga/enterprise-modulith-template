package ug.co.smsone.organization.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExternalOrganizationRepository extends JpaRepository<ExternalOrganization, UUID> {

    /**
     * THE id-keyed resolution: an {@code organization} claim carrying an id, or a provider webhook
     * naming an org. Backed by {@code uq_external_organization_ext_live}, whose leading column is the
     * provider — which is why every caller derives one instead of scanning on the issuer alone.
     *
     * <p>A scalar projection, not the entity, for the reason
     * {@code identity.internal.ExternalIdentityRepository#personIdByLink} gives: the edge wants one
     * uuid, and hydrating the row to read one column put a managed instance in the persistence context
     * on every authenticated request for nothing. This is HQL, so {@code @SQLRestriction} still hides a
     * soft-deleted link — which is what makes the answer match the PARTIAL unique index behind it.
     */
    @Query("select l.organizationId from ExternalOrganization l "
            + "where l.provider = :provider and l.issuer = :issuer and l.externalOrgId = :externalOrgId")
    Optional<UUID> organizationIdByExternalOrgId(@Param("provider") OrgProvider provider,
            @Param("issuer") String issuer, @Param("externalOrgId") String externalOrgId);

    /** The entity form of the same lookup — the link path needs the row, not just the id. */
    Optional<ExternalOrganization> findByProviderAndIssuerAndExternalOrgId(
            OrgProvider provider, String issuer, String externalOrgId);

    /**
     * The ALIAS-keyed resolution, and it is not a convenience: Keycloak's {@code organization} claim is
     * a map KEYED BY ALIAS — {@code {"acme":{"id":"…"}}} — so a claim can arrive with an alias and no
     * id at all. V11 indexes it ({@code uq_external_organization_alias_live}) for exactly this.
     */
    Optional<ExternalOrganization> findByProviderAndIssuerAndExternalAlias(
            OrgProvider provider, String issuer, String externalAlias);

    /** Is this tenant already linked at this issuer? The idempotency check the link path turns on. */
    Optional<ExternalOrganization> findByOrganizationIdAndProviderAndIssuer(
            UUID organizationId, OrgProvider provider, String issuer);
}
