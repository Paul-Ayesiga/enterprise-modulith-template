package ug.co.smsone.organization.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 *
 * <p>This service hands the controller VIEWS ({@link GroupDetail}, {@link GroupSummary}), never the
 * entity. That is not ceremony: {@link OrgGroup#getMembers()} is lazy and {@code open-in-view} is
 * false, so the member ids have to be materialized inside these transactions or not at all — and it
 * is what lets the listing answer with a member COUNT it can fetch for a whole page in one query.
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

    /**
     * One group as the API renders it, with its members materialized. Used by every SINGLE-group
     * response: the caller named this one group, and its member set is bounded by the organization's
     * headcount (which the plan's {@code MEMBERS_MAX} entitlement bounds in turn), so it is a fetch the
     * request asked for rather than one a page size hid.
     */
    record GroupDetail(UUID id, String name, String roleCode, List<UUID> members, Instant createdAt) {
    }

    /**
     * One group as a LISTING renders it: the member count, never the ids. See
     * {@link OrgGroupController#list} for why the ids are not here.
     */
    record GroupSummary(UUID id, String name, String roleCode, long memberCount, Instant createdAt) {
    }

    @Transactional
    GroupDetail create(UUID orgId, String name, String roleCode) {
        Role role = requireRole(orgId, roleCode);
        escalationGuard.requireCallerHolds(orgId, role.getPermissions());
        OrgGroup group = groups.save(OrgGroup.create(orgId, requireName(name), role.getId()));
        auditLog.record("organization.group_created", orgId, group.getId().toString(), null,
                "name=" + group.getName() + " role=" + role.getCode());
        // No members yet → no cache effect, and the role code is already in hand.
        return detail(group, role.getCode());
    }

    /**
     * One page of groups, at a cost the PAGE bounds: the entity query touches no member rows (the
     * collection is lazy now), the role codes come from the org-wide id → code map the member listing
     * uses — {@link RoleRepository#codeMapByOrgId}, one query, not a {@code findById} per row — and the
     * member counts come from one aggregate over the page's ids. Three queries for any page size.
     *
     * <p>Mapped HERE rather than in the controller because the mapping must happen inside this
     * transaction. {@link Window#map} keeps the original keyset position function (it is computed from
     * the entities, which this window still holds), so the cursor the controller mints is unaffected.
     */
    @Transactional(readOnly = true)
    Window<GroupSummary> list(UUID orgId, CursorPageRequest page) {
        Window<OrgGroup> window = groups.findBy((root, query, cb) -> cb.equal(root.get("orgId"), orgId),
                q -> q.limit(page.size()).sortBy(SORT).scroll(page.scrollPosition(SORT)));
        Map<UUID, String> roleCodes = roles.codeMapByOrgId(orgId);
        Map<UUID, Long> memberCounts = groups.memberCountMap(
                window.getContent().stream().map(OrgGroup::getId).toList());
        return window.map(group -> new GroupSummary(group.getId(), group.getName(),
                roleCodes.get(group.getRoleId()),
                memberCounts.getOrDefault(group.getId(), 0L), group.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    GroupDetail get(UUID orgId, UUID id) {
        return detail(require(orgId, id));
    }

    /** The entity, for the paths that go on to mutate it. Callers rendering a response want {@link #get}. */
    @Transactional(readOnly = true)
    OrgGroup require(UUID orgId, UUID id) {
        return groups.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("Group not found in this organization."));
    }

    @Transactional
    GroupDetail reassignRole(UUID orgId, UUID id, String roleCode) {
        OrgGroup group = require(orgId, id);
        Role role = requireRole(orgId, roleCode);
        escalationGuard.requireCallerHolds(orgId, role.getPermissions());
        String previous = roles.findById(group.getRoleId()).map(Role::getCode).orElse(null);
        group.reassignRole(role.getId());
        OrgGroup saved = groups.save(group);
        auditLog.record("organization.group_role_changed", orgId, id.toString(),
                "role=" + previous, "role=" + role.getCode());
        evict(); // every member's effective permissions just changed
        return detail(saved, role.getCode());
    }

    @Transactional
    void delete(UUID orgId, UUID id) {
        OrgGroup group = require(orgId, id);
        groups.delete(group);
        auditLog.record("organization.group_deleted", orgId, id.toString(), "name=" + group.getName(), null);
        evict();
    }

    @Transactional
    GroupDetail addMember(UUID orgId, UUID id, UUID personId) {
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
        return detail(group);
    }

    @Transactional
    GroupDetail removeMember(UUID orgId, UUID id, UUID personId) {
        OrgGroup group = require(orgId, id);
        if (group.removeMember(personId)) {
            groups.save(group);
            auditLog.record("organization.group_member_removed", orgId, id.toString(), "person=" + personId, null);
            evict();
        }
        return detail(group);
    }

    /**
     * Materializes one group for the wire. Must be called INSIDE the transaction that loaded the group:
     * {@code getMembers()} is lazy, and {@code List.copyOf} is what pins the ids into a value the
     * controller can still read after the transaction has closed.
     */
    private GroupDetail detail(OrgGroup group) {
        // codeMapByIds over a single id, not findById: the same two-column projection the listings use,
        // and it does not drag the role's EAGER permission set along for a string.
        return detail(group, roles.codeMapByIds(List.of(group.getRoleId())).get(group.getRoleId()));
    }

    /** {@link #detail(OrgGroup)} for the callers that already resolved the role. */
    private static GroupDetail detail(OrgGroup group, String roleCode) {
        return new GroupDetail(group.getId(), group.getName(), roleCode,
                List.copyOf(group.getMembers()), group.getCreatedAt());
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
