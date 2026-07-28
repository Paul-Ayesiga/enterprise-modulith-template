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
import ug.co.smsone.shared.error.UnauthorizedException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.ApiSource;

/** CRUD for org-scoped custom roles (bundles of permissions). System roles are read-only. */
@Service
class RoleService {

    private static final Set<String> RESERVED_CODES = Set.of("OWNER", "ADMIN", "MEMBER");

    private final RoleRepository roles;
    private final MembershipRepository memberships;
    private final PermissionResolver permissions;
    private final CurrentUserProvider currentUser;

    RoleService(RoleRepository roles, MembershipRepository memberships, PermissionResolver permissions,
            CurrentUserProvider currentUser) {
        this.roles = roles;
        this.memberships = memberships;
        this.permissions = permissions;
        this.currentUser = currentUser;
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
        Set<Permission> granted = toPermissions(permissionCodes);
        requireCallerHolds(orgId, granted);
        return roles.save(Role.create(orgId, code, name, false, description, granted));
    }

    @Transactional
    Role update(UUID orgId, UUID roleId, String name, String description, Set<String> permissionCodes) {
        Role role = require(orgId, roleId);
        Set<Permission> granted = toPermissions(permissionCodes);
        requireCallerHolds(orgId, granted);
        // requireEditable() inside replacePermissions/rename rejects system roles with a 403.
        role.replacePermissions(granted); // publishes RolePermissionsChanged
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

    /**
     * Privilege-escalation guard: a role editor may only grant permissions they themselves currently
     * hold in this org. Without it, anyone with {@code role:create}/{@code role:update} could mint a
     * role carrying permissions above their own (e.g. ADMIN — which lacks {@code org:delete} — granting
     * itself {@code org:delete}). OWNER holds everything, so it is unconstrained.
     */
    private void requireCallerHolds(UUID orgId, Set<Permission> granted) {
        CurrentUser caller = currentUser.currentUser()
                .orElseThrow(() -> new UnauthorizedException("Authentication required."));
        Set<String> held = permissions.resolve(caller.subject(), orgId);
        List<String> escalated = granted.stream()
                .map(Permission::code)
                .filter(code -> !held.contains(code))
                .sorted()
                .toList();
        if (!escalated.isEmpty()) {
            throw new ForbiddenException("You cannot grant permissions you do not hold: "
                    + String.join(", ", escalated));
        }
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
