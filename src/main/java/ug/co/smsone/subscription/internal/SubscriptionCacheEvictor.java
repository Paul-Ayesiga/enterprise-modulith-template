package ug.co.smsone.subscription.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.cache.TenantCacheKeys;
import ug.co.smsone.shared.cache.TwoLevelCacheManager;
import ug.co.smsone.subscription.SubscriptionChanged;

/**
 * A plan change must bite the next gate check — same urgency as the permission evictor.
 *
 * <p><b>It names the organization instead of taking its axis, and it has to (ADR 0010 §3.5).</b>
 * {@code org-entitlements} is a TENANT cache, so its keys carry the tenant; but
 * {@code @ApplicationModuleListener} runs after commit, on a pooled thread, inside its own
 * {@code REQUIRES_NEW} transaction — which is precisely where {@code TenantContext} refuses to pin,
 * because the connection is already bound and a pin there would be a silent no-op. The event carries
 * the org, so {@link TwoLevelCacheManager#evictForTenant} takes it directly. That is also why this
 * injects the two-level manager rather than the {@code CacheManager} interface: reaching a tenant's key
 * from a thread that is on no tenant is a deliberate capability and should read like one.
 */
@Component
class SubscriptionCacheEvictor {

    private final TwoLevelCacheManager caches;

    SubscriptionCacheEvictor(TwoLevelCacheManager caches) {
        this.caches = caches;
    }

    @ApplicationModuleListener
    void on(SubscriptionChanged event) {
        caches.evictForTenant(EntitlementResolver.CACHE, event.orgId(), TenantCacheKeys.wholeTenant());
    }
}
