package ug.co.smsone.organization.internal;

import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/** CRUD for org-scoped custom roles (bundles of permissions). System roles are read-only. */
@Service
class RoleService {

    /**
     * Only the code the application itself names is reserved. {@code ADMIN} and {@code MEMBER} used to
     * be seeded system roles and are now ordinary codes an owner may create, edit and delete — nothing
     * in the request path treats them specially.
     */
    private static final Set<String> RESERVED_CODES = Set.of(Role.OWNER_CODE);

    /**
     * Tenant roles may not borrow the platform vocabulary. The codes are inert to authorization, so
     * this is not a privilege boundary — it stops an org role reading like a platform tier in audit
     * trails, member lists and support conversations, where that would be genuinely misleading.
     */
    private static final String PLATFORM_PREFIX = "PLATFORM";

    private final RoleRepository roles;
    private final MembershipRepository memberships;
    private final PermissionEscalationGuard escalationGuard;
    private final AuditLog auditLog;
    private final Clock clock;

    RoleService(RoleRepository roles, MembershipRepository memberships,
            PermissionEscalationGuard escalationGuard, AuditLog auditLog, Clock clock) {
        this.roles = roles;
        this.memberships = memberships;
        this.escalationGuard = escalationGuard;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    private static final Sort ROLE_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    @Transactional(readOnly = true)
    Window<Role> list(UUID orgId, CursorPageRequest page) {
        return roles.findBy((root, query, cb) -> cb.equal(root.get("orgId"), orgId),
                q -> q.limit(page.size()).sortBy(ROLE_SORT).scroll(page.scrollPosition(ROLE_SORT)));
    }

    @Transactional(readOnly = true)
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
        if (code.startsWith(PLATFORM_PREFIX)) {
            throw new ValidationException("Role code '" + code + "' is reserved: organization roles "
                    + "cannot start with '" + PLATFORM_PREFIX + "'.",
                    ApiSource.pointer("/data/attributes/code"));
        }
        if (roles.findByOrgIdAndCode(orgId, code).isPresent()) {
            throw new ConflictException("A role with code '" + code + "' already exists.");
        }
        Set<Permission> granted = toPermissions(permissionCodes);
        escalationGuard.requireCallerHolds(orgId, granted);
        try {
            // Flush now so a concurrent same-code create surfaces here as the documented 409
            // (uq_org_role_org_code_live), not as a 500 at commit.
            Role saved = roles.saveAndFlush(Role.create(orgId, code, name, false, description, granted));
            auditLog.record("organization.role_created", orgId, code, null, codes(granted));
            return saved;
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("A role with code '" + code + "' already exists.");
        }
    }

    @Transactional
    Role update(UUID orgId, UUID roleId, String name, String description, Set<String> permissionCodes) {
        Role role = require(orgId, roleId);
        Set<Permission> granted = toPermissions(permissionCodes);
        escalationGuard.requireCallerHolds(orgId, granted);
        String previousPermissions = codes(role.getPermissions());
        // requireEditable() inside replacePermissions/rename rejects system roles with a 403.
        role.replacePermissions(granted); // publishes RolePermissionsChanged
        role.rename(name, description);
        Role saved = roles.save(role); // save() flushes AND publishes the registered event (cache eviction)
        auditLog.record("organization.role_updated", orgId, role.getCode(), previousPermissions, codes(granted));
        return saved;
    }

    @Transactional
    void delete(UUID orgId, UUID roleId) {
        // Exclusive lock first: soft delete no longer lets the FK backstop the membership check below
        // (the row survives, so the reference stays valid), and a concurrent invite or role assignment
        // would otherwise slip a LIVE membership onto a hidden role — a member with no permissions and
        // no error explaining why. Both writers take a shared lock on this same row before referencing
        // it, so the two serialize: whoever loses gets a 409 here or a 404 there, never silence.
        Role role = roles.lockById(roleId)
                .filter(candidate -> candidate.getOrgId().equals(orgId)) // never resolve another tenant's role
                .orElseThrow(() -> new NotFoundException("Role not found."));
        if (role.isSystemRole()) {
            throw new ForbiddenException("System roles cannot be deleted.");
        }
        // Only LIVE memberships count: @SQLRestriction hides soft-deleted ones, so a role whose last
        // member was removed is deletable — previously the FK made that impossible.
        if (memberships.existsByRoleId(roleId)) {
            throw new ConflictException("Role is still assigned to members; reassign them first.");
        }
        // Role.softDelete, not roles.delete: @SQLDelete would take role_permission down with it. See
        // the method's javadoc — this is the one aggregate whose payload IS the authorization data.
        role.softDelete(clock.instant());
        roles.saveAndFlush(role); // save() publishes the registered event (cache eviction)
        auditLog.record("organization.role_deleted", orgId, role.getCode(), codes(role.getPermissions()), null);
    }

    private static String codes(Set<Permission> permissions) {
        return permissions.stream().map(Permission::code).sorted().collect(Collectors.joining(", "));
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
