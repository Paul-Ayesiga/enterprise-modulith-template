package ug.co.smsone.subscription.internal;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.subscription.SubscriptionChanged;

/** A plan change must bite the next gate check — same urgency as the permission evictor. */
@Component
class SubscriptionCacheEvictor {

    private final CacheManager cacheManager;

    SubscriptionCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @ApplicationModuleListener
    void on(SubscriptionChanged event) {
        Cache cache = cacheManager.getCache(EntitlementResolver.CACHE);
        if (cache != null) {
            cache.evict(event.orgId().toString());
        }
    }
}
