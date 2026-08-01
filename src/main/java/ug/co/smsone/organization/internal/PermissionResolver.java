package ug.co.smsone.organization.internal;

import java.util.Set;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.Permission;

/**
 * Resolves a subject's effective permission codes in an org (org ACTIVE → membership → role →
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

    @Cacheable(cacheNames = CACHE, key = "#organizationId + ':' + #subject")
    public Set<String> resolve(String subject, UUID organizationId) {
        // A suspended (or locally unknown) org grants nothing to anyone — org status is enforced
        // here, inside the cached value, so a suspension plus its cache eviction is immediate.
        boolean orgActive = organizations.findByKcOrgId(organizationId)
                .map(org -> org.getStatus() == OrganizationStatus.ACTIVE)
                .orElse(false);
        if (!orgActive) {
            return Set.of();
        }
        // Effective permissions = the direct membership role UNION every group role the subject is
        // in. A group is a named assignment funnel, so its grants add to — never replace — the
        // member's own role. Group membership without an active org membership grants nothing:
        // groups extend a member, they are not an alternative door in.
        Set<String> effective = new java.util.HashSet<>();
        memberships.findByOrgIdAndUserSubject(organizationId, subject)
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .ifPresent(membership -> {
                    addRolePermissions(membership.getRoleId(), effective);
                    for (OrgGroup group : groups.findByOrgIdAndMember(organizationId, subject)) {
                        addRolePermissions(group.getRoleId(), effective);
                    }
                });
        return Set.copyOf(effective);
    }

    private void addRolePermissions(UUID roleId, Set<String> into) {
        roles.findById(roleId).ifPresent(role -> role.getPermissions().stream()
                .map(Permission::code).forEach(into::add));
    }
}
