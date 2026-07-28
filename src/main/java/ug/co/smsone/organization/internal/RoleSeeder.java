package ug.co.smsone.organization.internal;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.Permission;

/** Seeds the immutable system roles (OWNER/ADMIN/MEMBER) for an organization. Idempotent. */
@Component
class RoleSeeder {

    private final RoleRepository roles;

    RoleSeeder(RoleRepository roles) {
        this.roles = roles;
    }

    void seedSystemRoles(UUID orgId) {
        if (roles.existsByOrgId(orgId)) {
            return;
        }
        roles.save(Role.create(orgId, "OWNER", "Owner", true,
                "Full control of the organization", EnumSet.allOf(Permission.class)));

        Set<Permission> adminPermissions = EnumSet.allOf(Permission.class);
        adminPermissions.remove(Permission.ORG_DELETE);
        roles.save(Role.create(orgId, "ADMIN", "Administrator", true,
                "Manage members, roles and settings", adminPermissions));

        roles.save(Role.create(orgId, "MEMBER", "Member", true, "Read-only access",
                EnumSet.of(Permission.ORG_READ, Permission.MEMBER_READ, Permission.ROLE_READ,
                        Permission.ORG_SETTINGS_READ)));
    }
}
