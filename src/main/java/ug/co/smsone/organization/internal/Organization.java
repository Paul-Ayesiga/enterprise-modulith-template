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
import ug.co.smsone.organization.OrganizationRegistered;
import ug.co.smsone.organization.OrganizationStatusChanged;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** Local projection of a Keycloak organization. {@code kcOrgId} is the tenant key used everywhere. */
@Entity
@Table(name = "organization")
@SQLDelete(sql = "update organization set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class Organization extends SoftDeletableEntity {

    @Column(name = "kc_org_id", nullable = false, updatable = false)
    private UUID kcOrgId;

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

    static Organization register(UUID kcOrgId, String alias, String name) {
        Organization organization = new Organization();
        organization.kcOrgId = kcOrgId;
        organization.alias = alias;
        organization.name = name;
        organization.status = OrganizationStatus.ACTIVE;
        organization.registerEvent(new OrganizationRegistered(kcOrgId, alias, Instant.now()));
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
        registerEvent(new OrganizationStatusChanged(kcOrgId, status.name(), Instant.now()));
    }

    void reactivate() {
        if (status == OrganizationStatus.ACTIVE) {
            return;
        }
        this.status = OrganizationStatus.ACTIVE;
        registerEvent(new OrganizationStatusChanged(kcOrgId, status.name(), Instant.now()));
    }

    UUID getKcOrgId() {
        return kcOrgId;
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
