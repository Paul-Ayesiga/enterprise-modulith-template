package ug.co.smsone.organization.internal;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.organization.RolePermissionsChanged;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** An org-scoped role: a named, editable bundle of {@link Permission}s. System roles are immutable. */
@Entity
@Table(name = "org_role")
@SQLDelete(sql = "update org_role set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class Role extends SoftDeletableEntity {

    /**
     * The one role code the application itself names. Authorization never reads it — every request is
     * decided on permissions — but two lifecycle rules need a way to say "the owner": provisioning
     * attaches the first one, and the last one cannot be removed or demoted. Both are clearer as an
     * explicit constant than as a rule inferred from a permission set.
     */
    static final String OWNER_CODE = "OWNER";

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId; // Keycloak org id (tenant key)

    @Column(nullable = false, updatable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "system_role", nullable = false)
    private boolean systemRole;

    @Column(columnDefinition = "text")
    private String description;

    // EAGER is load-bearing, not habit: with open-in-view off, MemberService.invite reads this set
    // outside any transaction (after its Keycloak call), where LAZY would throw. The set is bounded
    // by the Permission enum, so the cost is one small secondary select per role load.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permission", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission", nullable = false, length = 64)
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = EnumSet.noneOf(Permission.class);

    protected Role() {
        // JPA
    }

    static Role create(UUID orgId, String code, String name, boolean systemRole,
            String description, Set<Permission> permissions) {
        Role role = new Role();
        role.orgId = orgId;
        role.code = code;
        role.name = name;
        role.systemRole = systemRole;
        role.description = description;
        role.permissions = permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
        return role;
    }

    void replacePermissions(Set<Permission> next) {
        requireEditable();
        this.permissions = next.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(next);
        registerEvent(new RolePermissionsChanged(orgId, getId(), Instant.now()));
    }

    /**
     * Catalog reconciliation for SYSTEM roles only (the {@link RoleSeeder}): when the Permission enum
     * gains a value, every existing org's OWNER must pick it up — otherwise "OWNER holds everything"
     * silently stops being true and no one can ever grant the new permission (the escalation guard
     * blocks granting what you don't hold). Not reachable from any user-facing path.
     */
    void reconcileSystemPermissions(Set<Permission> next) {
        if (!systemRole) {
            throw new IllegalStateException("Catalog reconciliation only applies to system roles.");
        }
        this.permissions = next.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(next);
        registerEvent(new RolePermissionsChanged(orgId, getId(), Instant.now()));
    }

    /**
     * Deletion as an ordinary versioned UPDATE, NOT through {@code @SQLDelete}. That annotation
     * overrides only the entity row's own delete statement; Hibernate still schedules the collection
     * remove for {@code role_permission}, so {@code repository.delete(role)} would stamp
     * {@code deleted_at} and hard-delete the permissions underneath it. The row would come back from
     * {@code SoftDeleteRecovery} granting nothing, and a restore that returns an empty authorization
     * bundle looks exactly like a restore that worked.
     *
     * <p>The event is what evicts the org's cached permission sets — a delete fires no
     * {@code @DomainEvents}, and this role's grants have unquestionably changed.
     */
    void softDelete(Instant when) {
        markDeleted(when);
        registerEvent(new RolePermissionsChanged(orgId, getId(), when));
    }

    void rename(String name, String description) {
        requireEditable();
        this.name = name;
        this.description = description;
    }

    private void requireEditable() {
        if (systemRole) {
            throw new ForbiddenException("System roles cannot be modified.");
        }
    }

    UUID getOrgId() {
        return orgId;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    boolean isSystemRole() {
        return systemRole;
    }

    String getDescription() {
        return description;
    }

    Set<Permission> getPermissions() {
        // A copy, never the live Hibernate collection: mutating that would bypass requireEditable()
        // and the RolePermissionsChanged event, and dirty checking would still flush it silently.
        return Set.copyOf(permissions);
    }
}
