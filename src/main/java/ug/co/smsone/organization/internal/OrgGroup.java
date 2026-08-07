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
     *
     * <p><b>LAZY, unlike {@link Role#getPermissions()} beside it</b>, and the difference is the bound:
     * a role's permissions are bounded by the {@code Permission} enum, a group's members are bounded by
     * the ORGANIZATION's headcount. EAGER meant every query that returned groups also dragged every
     * member row of every group it returned — the group LISTING therefore cost the org's total group
     * membership per page, no matter how small the page. {@code default_batch_fetch_size} makes that
     * one query instead of N; it does not make it fewer rows, which was the actual cost.
     *
     * <p>The trap LAZY sets in exchange: {@code open-in-view} is false, so this set may only be read
     * inside a service transaction. Reach for it via {@link OrgGroupService}'s materialized views —
     * never by handing an {@code OrgGroup} to a controller and calling {@code getMembers()} there.
     */
    @ElementCollection(fetch = FetchType.LAZY)
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
