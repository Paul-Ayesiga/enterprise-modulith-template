package ug.co.smsone.organization.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * One link between an {@link Organization} and the same tenant as some provider knows it: which
 * provider, at which issuer, under which id and alias over there.
 *
 * <p>This is the <b>only</b> place in this module permitted to store an identifier minted elsewhere —
 * the org-side twin of identity's {@code external_identity}, for the same reason. {@code kc_org_id} used
 * to be a column on {@code organization}, the module's port returned it, and so a Keycloak identifier
 * became the {@code org_id} of every other module, a public resource id, a Kill Bill external key and
 * the gateway's usage consumer id. Everything above {@link OrgResolver} speaks {@code organization.id}.
 *
 * <p>{@code externalOrgId} is a String, not a UUID: a Keycloak org id happens to be UUID-shaped, a
 * Google Workspace customer id is {@code C01abcdef}. Typing it to one provider's format would rebuild
 * the coupling this table exists to remove.
 *
 * <p>{@code issuer} is never null and never blank, for the reason V10 gives: Postgres treats NULLs as
 * distinct in a unique index, so a nullable issuer would let one provider org be claimed twice.
 */
@Entity
@Table(name = "external_organization")
@SQLDelete(sql = "update external_organization set deleted_at = now(), version = version + 1 "
        + "where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class ExternalOrganization extends SoftDeletableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private OrgProvider provider;

    /** The realm/tenant this org id is unique WITHIN. 'GOOGLE' names a product, not a namespace. */
    @Column(nullable = false, updatable = false, length = 300)
    private String issuer;

    @Column(name = "external_org_id", nullable = false, updatable = false, length = 255)
    private String externalOrgId;

    /**
     * The alias the provider knows this tenant by. Mutable: Keycloak's {@code organization} claim is a
     * map keyed by alias, so a rename over there has to be followable here or every token scoped to the
     * renamed org stops resolving.
     */
    @Column(name = "external_alias", length = 120)
    private String externalAlias;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected ExternalOrganization() {
        // JPA
    }

    static ExternalOrganization link(UUID organizationId, OrgProvider provider, String issuer,
            String externalOrgId, String externalAlias, Instant when) {
        ExternalOrganization link = new ExternalOrganization();
        link.organizationId = organizationId;
        link.provider = provider;
        link.issuer = issuer;
        link.externalOrgId = externalOrgId;
        link.externalAlias = externalAlias;
        link.linkedAt = when;
        return link;
    }

    void realias(String externalAlias) {
        this.externalAlias = externalAlias;
    }

    UUID getOrganizationId() {
        return organizationId;
    }

    OrgProvider getProvider() {
        return provider;
    }

    String getIssuer() {
        return issuer;
    }

    String getExternalOrgId() {
        return externalOrgId;
    }

    String getExternalAlias() {
        return externalAlias;
    }
}
