package ug.co.smsone.organization.internal;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** A named group that confers one org role to its members, on top of their direct membership role. */
@Entity
@Table(name = "org_group")
@SQLDelete(sql = "update org_group set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class OrgGroup extends SoftDeletableEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    /**
     * {@code person.id}, and half the primary key of {@code org_group_member} — which made it the
     * sharpest case of the old shape: a Keycloak subject was not merely referenced here, it was
     * load-bearing key data in a table that could not point at the thing it keyed on. Still a soft ref
     * (person is another module), but now at least it is our identifier.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "org_group_member", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "person_id", nullable = false)
    private Set<UUID> members = new LinkedHashSet<>();

    protected OrgGroup() {
        // JPA
    }

    static OrgGroup create(UUID orgId, String name, UUID roleId) {
        OrgGroup group = new OrgGroup();
        group.orgId = orgId;
        group.name = name;
        group.roleId = roleId;
        return group;
    }

    void rename(String name) {
        this.name = name;
    }

    void reassignRole(UUID roleId) {
        this.roleId = roleId;
    }

    boolean addMember(UUID personId) {
        return members.add(personId);
    }

    boolean removeMember(UUID personId) {
        return members.remove(personId);
    }

    UUID getOrgId() {
        return orgId;
    }

    String getName() {
        return name;
    }

    UUID getRoleId() {
        return roleId;
    }

    Set<UUID> getMembers() {
        return members;
    }
}
