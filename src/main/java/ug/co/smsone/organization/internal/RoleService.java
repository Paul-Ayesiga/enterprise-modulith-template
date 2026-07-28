package ug.co.smsone.organization.internal;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/** CRUD for org-scoped custom roles (bundles of permissions). System roles are read-only. */
@Service
class RoleService {

    private static final Set<String> RESERVED_CODES = Set.of("OWNER", "ADMIN", "MEMBER");

    private final RoleRepository roles;
    private final MembershipRepository memberships;

    RoleService(RoleRepository roles, MembershipRepository memberships) {
        this.roles = roles;
        this.memberships = memberships;
    }

    List<Role> list(UUID orgId) {
        return roles.findByOrgId(orgId);
    }

    Role require(UUID orgId, UUID roleId) {
        return roles.findById(roleId)
                .filter(role -> role.getOrgId().equals(orgId)) // never resolve a role from another tenant
                .orElseThrow(() -> new NotFoundException("Role not found."));
    }

    @Transactional
    Role create(UUID orgId, String rawCode, String name, String description, Set<String> permissionCodes) {
        String code = normalizeCode(rawCode);
        if (RESERVED_CODES.contains(code)) {
            throw new ConflictException("Role code '" + code + "' is reserved for a system role.");
        }
        if (roles.findByOrgIdAndCode(orgId, code).isPresent()) {
            throw new ConflictException("A role with code '" + code + "' already exists.");
        }
        return roles.save(Role.create(orgId, code, name, false, description, toPermissions(permissionCodes)));
    }

    @Transactional
    Role update(UUID orgId, UUID roleId, String name, String description, Set<String> permissionCodes) {
        Role role = require(orgId, roleId);
        // requireEditable() inside replacePermissions/rename rejects system roles with a 403.
        role.replacePermissions(toPermissions(permissionCodes)); // publishes RolePermissionsChanged
        role.rename(name, description);
        return roles.save(role); // save() flushes AND publishes the registered event (cache eviction)
    }

    @Transactional
    void delete(UUID orgId, UUID roleId) {
        Role role = require(orgId, roleId);
        if (role.isSystemRole()) {
            throw new ForbiddenException("System roles cannot be deleted.");
        }
        if (memberships.existsByRoleId(roleId)) {
            throw new ConflictException("Role is still assigned to members; reassign them first.");
        }
        roles.delete(role);
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    private static Set<Permission> toPermissions(Set<String> codes) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        Set<String> unknown = new LinkedHashSet<>();
        for (String code : codes) {
            if (Permission.isValid(code)) {
                permissions.add(Permission.fromCode(code));
            } else {
                unknown.add(code);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ValidationException("Unknown permission code(s): " + String.join(", ", unknown),
                    ApiSource.pointer("/data/attributes/permissions"));
        }
        return permissions;
    }
}
