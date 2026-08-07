package ug.co.smsone.organization.internal;

import java.util.UUID;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Org user groups. A group grants its role to members ON TOP of their own, so creating a group,
 * re-roling it, or adding a member all pass the {@link PermissionEscalationGuard} — handing someone
 * a group role is still handing them its permissions. Group mutations clear the permission cache
 * DIRECTLY (there is no group domain event, and the effect must be immediate like a role change).
 */
@Service
class OrgGroupService {

    private static final Sort SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrgGroupRepository groups;
    private final RoleRepository roles;
    private final MembershipRepository memberships;
    private final PermissionEscalationGuard escalationGuard;
    private final CacheManager cacheManager;
    private final AuditLog auditLog;

    OrgGroupService(OrgGroupRepository groups, RoleRepository roles, MembershipRepository memberships,
            PermissionEscalationGuard escalationGuard, CacheManager cacheManager, AuditLog auditLog) {
        this.groups = groups;
        this.roles = roles;
        this.memberships = memberships;
        this.escalationGuard = escalationGuard;
        this.cacheManager = cacheManager;
        this.auditLog = auditLog;
    }

    @Transactional
    OrgGroup create(UUID orgId, String name, String roleCode) {
        Role role = requireRole(orgId, roleCode);
        escalationGuard.requireCallerHolds(orgId, role.getPermissions());
        OrgGroup group = groups.save(OrgGroup.create(orgId, requireName(name), role.getId()));
        auditLog.record("organization.group_created", orgId, group.getId().toString(), null,
                "name=" + group.getName() + " role=" + role.getCode());
        return group; // no members yet → no cache effect
    }

    @Transactional(readOnly = true)
    Window<OrgGroup> list(UUID orgId, CursorPageRequest page) {
        return groups.findBy((root, query, cb) -> cb.equal(root.get("orgId"), orgId),
                q -> q.limit(page.size()).sortBy(SORT).scroll(page.scrollPosition(SORT)));
    }

    @Transactional(readOnly = true)
    OrgGroup require(UUID orgId, UUID id) {
        return groups.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("Group not found in this organization."));
    }

    @Transactional
    OrgGroup reassignRole(UUID orgId, UUID id, String roleCode) {
        OrgGroup group = require(orgId, id);
        Role role = requireRole(orgId, roleCode);
        escalationGuard.requireCallerHolds(orgId, role.getPermissions());
        String previous = roles.findById(group.getRoleId()).map(Role::getCode).orElse(null);
        group.reassignRole(role.getId());
        OrgGroup saved = groups.save(group);
        auditLog.record("organization.group_role_changed", orgId, id.toString(),
                "role=" + previous, "role=" + role.getCode());
        evict(); // every member's effective permissions just changed
        return saved;
    }

    @Transactional
    void delete(UUID orgId, UUID id) {
        OrgGroup group = require(orgId, id);
        groups.delete(group);
        auditLog.record("organization.group_deleted", orgId, id.toString(), "name=" + group.getName(), null);
        evict();
    }

    @Transactional
    OrgGroup addMember(UUID orgId, UUID id, UUID personId) {
        OrgGroup group = require(orgId, id);
        // Only an existing org member can be grouped — a group extends membership, it is not a way in.
        if (memberships.findByOrgIdAndPersonId(orgId, personId).isEmpty()) {
            throw new NotFoundException("That person is not a member of this organization.");
        }
        // Adding to a group grants that group's role: same escalation rule as inviting into it.
        roles.findById(group.getRoleId())
                .ifPresent(role -> escalationGuard.requireCallerHolds(orgId, role.getPermissions()));
        if (group.addMember(personId)) {
            groups.save(group);
            auditLog.record("organization.group_member_added", orgId, id.toString(), null, "person=" + personId);
            evict();
        }
        return group;
    }

    @Transactional
    OrgGroup removeMember(UUID orgId, UUID id, UUID personId) {
        OrgGroup group = require(orgId, id);
        if (group.removeMember(personId)) {
            groups.save(group);
            auditLog.record("organization.group_member_removed", orgId, id.toString(), "person=" + personId, null);
            evict();
        }
        return group;
    }

    String roleCode(UUID roleId) {
        return roles.findById(roleId).map(Role::getCode).orElse(null);
    }

    private Role requireRole(UUID orgId, String roleCode) {
        return roles.findByOrgIdAndCode(orgId, roleCode == null ? "" : roleCode.trim().toUpperCase())
                .orElseThrow(() -> new NotFoundException("Role '" + roleCode + "' not found in this organization."));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ug.co.smsone.shared.error.ValidationException("Group name is required (max 100 characters).",
                    ug.co.smsone.shared.web.ApiSource.pointer("/data/attributes/name"));
        }
        return name.trim();
    }

    /** Coarse clear-all, exactly like OrgPermissionCacheEvictor — the org's members are few. */
    private void evict() {
        Cache cache = cacheManager.getCache(PermissionResolver.CACHE);
        if (cache != null) {
            cache.clear();
        }
    }
}
