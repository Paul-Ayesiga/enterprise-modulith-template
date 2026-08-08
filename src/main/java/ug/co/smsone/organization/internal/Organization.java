package ug.co.smsone.organization.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.organization.OrganizationStatusChanged;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * The tenant. Its {@code id} is the tenant key used everywhere — this row is no longer a projection of
 * something Keycloak owns. The provider's organization id lives in {@link ExternalOrganization} and is
 * resolved through {@link OrgResolver}; V11's header records what it cost to have it here instead.
 *
 * <p>{@code alias} is the local slug and also the key Keycloak's {@code organization} claim is keyed by,
 * but the claim is resolved through the link table — an alias is never trusted as an identifier.
 */
@Entity
@Table(name = "organization", schema = "platform")
@SQLDelete(sql = "update platform.organization set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class Organization extends SoftDeletableEntity {

    @Column(nullable = false, length = 120)
    private String alias;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrganizationStatus status;

    protected Organization() {
        // JPA
    }

    /**
     * No {@code OrganizationRegistered} is registered here, and that is forced rather than chosen: the
     * id is assigned by Hibernate at persist, so inside this factory there is no tenant key to put in
     * the event. {@link OrgProjectionWriter} publishes it once the row exists — the same explicit
     * publication a delete already needs, and in the same transaction, so listeners see no difference.
     */
    static Organization register(String alias, String name) {
        Organization organization = new Organization();
        organization.alias = alias;
        organization.name = name;
        organization.status = OrganizationStatus.ACTIVE;
        return organization;
    }

    void rename(String name) {
        this.name = name;
    }

    void suspend() {
        if (status == OrganizationStatus.SUSPENDED) {
            return; // idempotent — no spurious event/cache flush
        }
        this.status = OrganizationStatus.SUSPENDED;
        registerEvent(new OrganizationStatusChanged(getId(), status.name(), Instant.now()));
    }

    void reactivate() {
        if (status == OrganizationStatus.ACTIVE) {
            return;
        }
        this.status = OrganizationStatus.ACTIVE;
        registerEvent(new OrganizationStatusChanged(getId(), status.name(), Instant.now()));
    }

    String getAlias() {
        return alias;
    }

    String getName() {
        return name;
    }

    OrganizationStatus getStatus() {
        return status;
    }
}
