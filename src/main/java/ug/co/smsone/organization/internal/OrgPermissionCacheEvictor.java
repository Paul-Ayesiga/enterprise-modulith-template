package ug.co.smsone.organization.internal;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.MemberRemoved;
import ug.co.smsone.organization.MembershipRoleChanged;
import ug.co.smsone.organization.RolePermissionsChanged;

/**
 * Evicts cached org permissions when a role's permissions or a membership changes. All-entries
 * eviction is coarse but correct and simple; the two-level cache broadcasts the invalidation to
 * other instances' L1.
 *
 * <p>Deliberately does NOT listen to {@code MembershipCreated}: a subject's permissions are only ever
 * resolved (and cached) for an authenticated, org-scoped request, which requires a token already
 * carrying that org — impossible before the membership exists — so no stale empty entry can precede an
 * invite. Every event handled here can only REDUCE a live member's permissions, which must take effect.
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
    void onMembershipRoleChanged(MembershipRoleChanged event) {
        evict();
    }

    @ApplicationModuleListener
    void onMemberRemoved(MemberRemoved event) {
        evict();
    }

    private void evict() {
        Cache cache = cacheManager.getCache(PermissionResolver.CACHE);
        if (cache != null) {
            cache.clear();
        }
    }
}
