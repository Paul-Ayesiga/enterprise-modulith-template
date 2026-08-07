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
import ug.co.smsone.organization.MembershipCreated;
import ug.co.smsone.organization.MembershipRoleChanged;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** A person's membership in an organization with a single role. Keyed by (orgId, personId). */
@Entity
@Table(name = "membership")
@SQLDelete(sql = "update membership set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class Membership extends SoftDeletableEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId; // organization.id — a real FK in the schema; same module (V11)

    /**
     * {@code person.id} — a SOFT ref with no foreign key: identity is another module (AGENTS §1). It is
     * our uuid now rather than a Keycloak sub, and that changes nothing about the rule.
     */
    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status;

    protected Membership() {
        // JPA
    }

    static Membership create(UUID orgId, UUID personId, UUID roleId, String roleCode) {
        Membership membership = new Membership();
        membership.orgId = orgId;
        membership.personId = personId;
        membership.roleId = roleId;
        membership.status = MembershipStatus.ACTIVE;
        membership.registerEvent(new MembershipCreated(orgId, personId, roleCode, Instant.now()));
        return membership;
    }

    void assignRole(UUID newRoleId) {
        this.roleId = newRoleId;
        registerEvent(new MembershipRoleChanged(orgId, personId, Instant.now()));
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getPersonId() {
        return personId;
    }

    UUID getRoleId() {
        return roleId;
    }

    MembershipStatus getStatus() {
        return status;
    }
}
