package ug.co.smsone.organization.internal;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.Permission;

/**
 * Resolves a subject's effective permission codes in an org (membership → role → permissions).
 * Cached in the two-level cache; a role/membership change clears it via {@link OrgPermissionCacheEvictor}.
 * A separate bean so the cached call goes through the proxy (self-invocation wouldn't).
 */
@Component
class PermissionResolver {

    static final String CACHE = "org-permissions";

    private final MembershipRepository memberships;
    private final RoleRepository roles;

    PermissionResolver(MembershipRepository memberships, RoleRepository roles) {
        this.memberships = memberships;
        this.roles = roles;
    }

    @Cacheable(cacheNames = CACHE, key = "#organizationId + ':' + #subject")
    public Set<String> resolve(String subject, UUID organizationId) {
        return memberships.findByOrgIdAndUserSubject(organizationId, subject)
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .flatMap(membership -> roles.findById(membership.getRoleId()))
                .map(role -> role.getPermissions().stream()
                        .map(Permission::code)
                        .collect(Collectors.toUnmodifiableSet()))
                .orElseGet(Set::of);
    }
}
