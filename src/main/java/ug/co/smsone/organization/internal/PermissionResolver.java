package ug.co.smsone.organization.internal;

import java.util.Set;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.Permission;

/**
 * Resolves a person's effective permission codes in an org (org ACTIVE → membership → role →
 * permissions). Cached in the two-level cache; a role/membership/org-status change clears it via
 * {@link OrgPermissionCacheEvictor}. A separate bean so the cached call goes through the proxy
 * (self-invocation wouldn't).
 */
@Component
class PermissionResolver {

    static final String CACHE = "org-permissions";

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final OrgGroupRepository groups;

    PermissionResolver(OrganizationRepository organizations, MembershipRepository memberships,
            RoleRepository roles, OrgGroupRepository groups) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.roles = roles;
        this.groups = groups;
    }

    /**
     * Both halves of the cache key are UUIDs of ours now. The composite key is unchanged in shape but
     * no longer mixes id spaces: it used to concatenate a Keycloak org id with a Keycloak subject.
     */
    @Cacheable(cacheNames = CACHE, key = "#organizationId + ':' + #personId", sync = true)
    public Set<String> resolve(UUID personId, UUID organizationId) {
        if (personId == null || organizationId == null) {
            return Set.of(); // a machine key, or a caller scoped to no tenant — holds nothing here
        }
        // A suspended (or locally unknown) org grants nothing to anyone — org status is enforced
        // here, inside the cached value, so a suspension plus its cache eviction is immediate.
        boolean orgActive = organizations.findById(organizationId)
                .map(org -> org.getStatus() == OrganizationStatus.ACTIVE)
                .orElse(false);
        if (!orgActive) {
            return Set.of();
        }
        // Effective permissions = the direct membership role UNION every group role the person is
        // in. A group is a named assignment funnel, so its grants add to — never replace — the
        // member's own role. Group membership without an active org membership grants nothing:
        // groups extend a member, they are not an alternative door in.
        //
        // The role ids are collected FIRST and fetched in one findAllById. A findById per group was
        // 1+N on a path that also drags an EAGER role_permission select per row, and while this whole
        // method sits inside the cached value, the miss is per (org, person) and the L2 TTL is ten
        // minutes — so every active member re-paid it several times an hour, on every node.
        Set<UUID> roleIds = new java.util.LinkedHashSet<>();
        memberships.findByOrgIdAndPersonId(organizationId, personId)
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .ifPresent(membership -> {
                    roleIds.add(membership.getRoleId());
                    for (OrgGroup group : groups.findByOrgIdAndMember(organizationId, personId)) {
                        roleIds.add(group.getRoleId());
                    }
                });
        roleIds.remove(null); // a group with no role assigned yet contributes nothing, not a null probe
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        Set<String> effective = new java.util.HashSet<>();
        for (Role role : roles.findAllById(roleIds)) {
            role.getPermissions().stream().map(Permission::code).forEach(effective::add);
        }
        return Set.copyOf(effective);
    }
}
