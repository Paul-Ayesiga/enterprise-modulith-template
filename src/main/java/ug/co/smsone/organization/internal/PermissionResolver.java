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
 *
 * <h2>Axis contract: this method must run on {@code organizationId}'s OWN axis (ADR 0010 §3.2)</h2>
 *
 * <p>Three of the four tables it reads are TENANT-tier — {@code membership}, {@code org_role} and
 * {@code role_permission} (docs/DATA_MODEL.md) — and a tenant-tier table is addressed BARE, so the
 * {@code search_path} on the borrowed connection is the only thing that says which schema it means.
 * On a PLATFORM axis they are not merely the wrong tenant's rows, they are no rows at all:
 * {@code relation "org_role" does not exist}. The fourth, {@code platform.organization}, is reached by
 * name and so resolves from either axis — which is why one tenant span covers the whole method and the
 * org-status check can stay inside the cached value where a suspension's eviction reaches it.
 *
 * <p><b>The pin is not taken here, and that is not an omission.</b> This method is {@code @Cacheable}
 * and is called from two directions with different obligations:
 *
 * <ul>
 *   <li>{@link OrgAuthorizationImpl} — the port, reached from OUTSIDE the module by callers that have
 *       no reason to know these tables are the tenant's (the edge, on the platform axis; a scheduler,
 *       on none). It pins, because it is the boundary where the module's own knowledge lives.</li>
 *   <li>{@link PermissionEscalationGuard} — reached from INSIDE, and always already on the axis:
 *       every one of its callers is serving a request, a tool call or an exchange job for the very org
 *       being asked about, and all three pin before they open a transaction ({@code CurrentUserFilter},
 *       {@code McpToolDispatcher}, {@code ExchangeWorker}). Several of them ARE inside a transaction by
 *       the time the guard runs — {@code RoleService.create} is — and a pin there would throw for
 *       re-declaring an axis the connection is already on. So the guard must not pin, and does not.</li>
 * </ul>
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
     *
     * <h3>The organization stays in this key. PERMANENTLY — this is the third time it has been asked</h3>
     *
     * <p>{@code CacheRegistry} declares this cache {@link ug.co.smsone.shared.cache.CacheScope#TENANT},
     * so {@code TwoLevelCache} prefixes the thread's tenant into the key at both levels, and on every
     * request path the organization here is redundant with that prefix. It has been proposed for removal
     * twice on exactly that observation, deferred to "Phase 5" the second time, and Phase 5 has now
     * happened. <b>The answer is that it stays, and the reason is structural rather than transitional.</b>
     *
     * <p><b>The prefix comes from the AXIS; this key component comes from the ARGUMENT.</b> They agree
     * only when the axis names the very organization being asked about. A caller that pins one tenant
     * and asks about another files its answers under the pin — {@code TwoLevelCache}'s class note states
     * this as the general rule, and {@code TenantCacheKeys.forThisTenant} is how {@code org-entitlements}
     * closes it, by <em>throwing</em> unless the two agree. This cache cannot take that option: its
     * callers legitimately ask about an organization they are not on.
     *
     * <p><b>And Phase 5 made that permanent rather than removing it, because the fan-out is per HOME,
     * not per TENANT.</b> ADR 0010 §3.4 fixes the iteration source as "{@code tenant_pool} plus the
     * silos — bounded by the silo ceiling, not by tenant count", and §1/§5 measure why the alternative
     * is not shippable into this database at 5,000 orgs. {@code TenantHome.pool()} is the shape that
     * took: one axis, the non-organization uuid {@code new UUID(0L, 0L)}, standing for the entire shared
     * schema. So a sweep over the pooled home asks this question for many different organizations under
     * one prefix — {@code ExchangeScheduleFiringJob} does it today, per due schedule, inside a
     * transaction where {@link OrgAuthorizationImpl} deliberately does not re-pin. Two organizations,
     * one prefix, one cache: with the organization gone from the key, a person who belongs to both would
     * get whichever set was resolved first. That is a cross-tenant authorization bypass, and AGENTS §5.5
     * is the rule it breaks.
     *
     * <p><b>Pooling is the default and stays the default</b> (§1, §4.3: a new org lands in
     * {@code tenant_pool} and signup does no DDL), so the pooled home is never empty and never becomes a
     * single tenant. There is no later phase in which this component becomes redundant. What removing it
     * would actually require is per-ORGANIZATION iteration, which is the design ADR 0010 rejects on
     * measurement in its first section.
     *
     * <p><b>The price of keeping it, stated so nobody pays it twice.</b> These entries are one per
     * MEMBER, under a prefix that may be the asking axis rather than the organization, so no caller
     * outside this module can reach one tenant's entries by key. Every eviction of this cache is
     * therefore a {@code clear()} — {@link OrgPermissionCacheEvictor}, {@link OrgGroupService}, and
     * {@code TenantPromotionCaches} at a promotion's placement flip — which spans all prefixes and all
     * keys and so cannot strand an entry. That is the trade: a longer key and coarse eviction, in
     * exchange for two tenants never sharing an authorization answer.
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
