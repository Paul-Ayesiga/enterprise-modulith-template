package ug.co.smsone.shared.cache;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/** Lazily pairs a Caffeine cache with its Valkey/Redis counterpart per cache name. */
public class TwoLevelCacheManager implements CacheManager {

    private final CacheManager l1Manager;
    private final CacheManager l2Manager;
    private final CacheInvalidationBroadcaster broadcaster;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final Map<String, TwoLevelCache> caches = new ConcurrentHashMap<>();

    public TwoLevelCacheManager(CacheManager l1Manager, CacheManager l2Manager,
            CacheInvalidationBroadcaster broadcaster, io.micrometer.core.instrument.MeterRegistry meters) {
        this.l1Manager = l1Manager;
        this.l2Manager = l2Manager;
        this.broadcaster = broadcaster;
        this.meters = meters;
    }

    @Override
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name, cacheName -> new TwoLevelCache(
                cacheName,
                l1Manager.getCache(cacheName),
                l2Manager == null ? null : l2Manager.getCache(cacheName),
                broadcaster,
                meters));
    }

    @Override
    public Collection<String> getCacheNames() {
        return Set.copyOf(caches.keySet()); // the live keySet view would let a caller detach caches
    }

    /** L1-only eviction on invalidation messages from other instances. */
    void evictLocal(String cacheName, String key) {
        TwoLevelCache cache = caches.get(cacheName);
        if (cache != null) {
            cache.evictLocal(key);
        }
    }
}
