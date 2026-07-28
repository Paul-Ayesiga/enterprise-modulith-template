package ug.co.smsone.organization.internal;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.Permission;

/**
 * Seeds AND reconciles the immutable system roles (OWNER/ADMIN/MEMBER) for an organization.
 * Idempotent per-role upsert: a missing role is created; an existing one whose permission set has
 * drifted from the catalog (a new {@link Permission} enum value shipped) is updated — otherwise
 * "OWNER holds everything" would silently stop being true for pre-existing orgs, and the escalation
 * guard would make the new permission permanently ungrantable there. Custom roles are never touched.
 */
@Component
class RoleSeeder {

    record SystemRoleDefinition(String code, String name, String description, Set<Permission> permissions) {
    }

    private final RoleRepository roles;

    RoleSeeder(RoleRepository roles) {
        this.roles = roles;
    }

    static List<SystemRoleDefinition> systemRoleDefinitions() {
        Set<Permission> adminPermissions = EnumSet.allOf(Permission.class);
        adminPermissions.remove(Permission.ORG_DELETE);
        return List.of(
                new SystemRoleDefinition("OWNER", "Owner", "Full control of the organization",
                        EnumSet.allOf(Permission.class)),
                new SystemRoleDefinition("ADMIN", "Administrator", "Manage members, roles and settings",
                        adminPermissions),
                new SystemRoleDefinition("MEMBER", "Member", "Read-only access",
                        EnumSet.of(Permission.ORG_READ, Permission.MEMBER_READ, Permission.ROLE_READ,
                                Permission.ORG_SETTINGS_READ)));
    }

    @Transactional
    void seedSystemRoles(UUID orgId) {
        for (SystemRoleDefinition definition : systemRoleDefinitions()) {
            Role existing = roles.findByOrgIdAndCode(orgId, definition.code()).orElse(null);
            if (existing == null) {
                roles.save(Role.create(orgId, definition.code(), definition.name(), true,
                        definition.description(), definition.permissions()));
            } else if (existing.isSystemRole() && !existing.getPermissions().equals(definition.permissions())) {
                existing.reconcileSystemPermissions(definition.permissions()); // publishes RolePermissionsChanged
                roles.save(existing);
            }
        }
    }
}
