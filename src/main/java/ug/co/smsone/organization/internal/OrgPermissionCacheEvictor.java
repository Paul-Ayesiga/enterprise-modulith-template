package ug.co.smsone.organization.internal;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.MemberRemoved;
import ug.co.smsone.organization.MembershipCreated;
import ug.co.smsone.organization.MembershipRoleChanged;
import ug.co.smsone.organization.OrganizationStatusChanged;
import ug.co.smsone.organization.RolePermissionsChanged;

/**
 * Evicts cached org permissions when a role's permissions or a membership changes. All-entries
 * eviction is coarse but correct and simple; the two-level cache broadcasts the invalidation to
 * other instances' L1.
 *
 * <p>{@code MembershipCreated} must evict too: a stale EMPTY entry can precede the membership — e.g.
 * a removed member's still-valid JWT (org claim lives until expiry) drives a request that re-caches
 * {@code Set.of()} after the removal's eviction; without eviction here, a re-invite would leave them
 * locked out until the entry ages out of L2.
 */
@Component
class OrgPermissionCacheEvictor {

    private final CacheManager cacheManager;

    OrgPermissionCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @ApplicationModuleListener
    void onRolePermissionsChanged(RolePermissionsChanged event) {
        evict();
    }

    @ApplicationModuleListener
    void onMembershipCreated(MembershipCreated event) {
        evict();
    }

    @ApplicationModuleListener
    void onMembershipRoleChanged(MembershipRoleChanged event) {
        evict();
    }

    @ApplicationModuleListener
    void onMemberRemoved(MemberRemoved event) {
        evict();
    }

    @ApplicationModuleListener
    void onOrganizationStatusChanged(OrganizationStatusChanged event) {
        evict(); // suspension must cut cached access immediately
    }

    @ApplicationModuleListener
    void onOrganizationDeleted(ug.co.smsone.organization.OrganizationDeleted event) {
        evict(); // same urgency as suspension: cached grants must not outlive the tenant
    }

    private void evict() {
        Cache cache = cacheManager.getCache(PermissionResolver.CACHE);
        if (cache != null) {
            cache.clear();
        }
    }
}
