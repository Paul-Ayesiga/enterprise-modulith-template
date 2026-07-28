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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.organization.RolePermissionsChanged;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.persistence.AggregateRoot;

/** An org-scoped role: a named, editable bundle of {@link Permission}s. System roles are immutable. */
@Entity
@Table(name = "org_role", uniqueConstraints = @UniqueConstraint(columnNames = {"org_id", "code"}))
class Role extends AggregateRoot {

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
     * gains a value, existing orgs' OWNER/ADMIN must pick it up — otherwise "OWNER holds everything"
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
        return permissions;
    }
}
