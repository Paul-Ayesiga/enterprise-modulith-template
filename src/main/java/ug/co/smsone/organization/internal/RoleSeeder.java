package ug.co.smsone.organization.internal;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.Permission;

/**
 * Seeds AND reconciles the one immutable system role — {@code OWNER} — for an organization.
 * Idempotent upsert: a missing role is created; an existing one whose permission set has drifted from
 * the catalog (a new {@link Permission} enum value shipped) is updated — otherwise "OWNER holds
 * everything" would silently stop being true for pre-existing orgs, and the escalation guard would
 * make the new permission permanently ungrantable there. Custom roles are never touched.
 *
 * <p>OWNER is the <em>only</em> seeded role by design: every other org role is a permission bundle the
 * owner creates, and no code path names it. Shipping an {@code ADMIN}/{@code MEMBER} pair would have
 * made two arbitrary permission sets look canonical, and tempted checks to key on the code rather than
 * on the permission.
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
        return List.of(
                new SystemRoleDefinition(Role.OWNER_CODE, "Owner", "Full control of the organization",
                        EnumSet.allOf(Permission.class)));
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
