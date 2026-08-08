package ug.co.smsone.shared.cache;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Lazily pairs a Caffeine cache with its Valkey/Redis counterpart per cache name, and refuses to
 * produce one for a name {@link CacheRegistry} does not classify (ADR 0010 §3.5).
 *
 * <p><b>Creating on demand was the hole.</b> The manager used to mint a cache for any name a
 * {@code @Cacheable} happened to mention, so classifying a cache was a thing a reviewer might notice
 * rather than a thing the code required. Resolving the scope here — before the cache exists — makes the
 * classification the price of admission: {@link #getCache} throws on an undeclared name, and the
 * {@link CacheScope} it resolves is what {@link TwoLevelCache} then uses to decide whether the tenant
 * belongs in the key.
 */
public class TwoLevelCacheManager implements CacheManager {

    private final CacheRegistry registry;
    private final CacheManager l1Manager;
    private final CacheManager l2Manager;
    private final CacheInvalidationBroadcaster broadcaster;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final Map<String, TwoLevelCache> caches = new ConcurrentHashMap<>();

    public TwoLevelCacheManager(CacheRegistry registry, CacheManager l1Manager, CacheManager l2Manager,
            CacheInvalidationBroadcaster broadcaster, io.micrometer.core.instrument.MeterRegistry meters) {
        this.registry = registry;
        this.l1Manager = l1Manager;
        this.l2Manager = l2Manager;
        this.broadcaster = broadcaster;
        this.meters = meters;
    }

    /**
     * @throws IllegalArgumentException if {@code name} is not declared in {@link CacheRegistry} — a
     *     cache nobody classified is the one that leaks, so it is never created. Never returns null:
     *     the two outcomes are a classified cache or a loud failure.
     */
    @Override
    public Cache getCache(String name) {
        CacheScope scope = registry.scopeOf(name);
        return caches.computeIfAbsent(name, cacheName -> new TwoLevelCache(
                cacheName,
                scope,
                l1Manager.getCache(cacheName),
                l2Manager == null ? null : l2Manager.getCache(cacheName),
                broadcaster,
                meters));
    }

    @Override
    public Collection<String> getCacheNames() {
        return Set.copyOf(caches.keySet()); // the live keySet view would let a caller detach caches
    }

    /**
     * Evicts one TENANT cache entry for a NAMED organization, rather than for whichever one the thread
     * is pinned to. The shape an after-commit listener needs: {@code @ApplicationModuleListener} runs in
     * its own transaction on a pooled thread, so it has no tenant axis and cannot take one — a pin
     * inside an active transaction is the silent no-op {@code TenantContext} throws to prevent.
     *
     * @throws IllegalArgumentException if the cache is undeclared, or is declared
     *     {@link CacheScope#GLOBAL} — a global cache has no per-tenant key to reach, and asking for one
     *     means the caller has the wrong cache.
     */
    public void evictForTenant(String cacheName, UUID orgId, Object key) {
        if (registry.scopeOf(cacheName) != CacheScope.TENANT) {
            throw new IllegalArgumentException("Cache '" + cacheName + "' is declared " + CacheScope.GLOBAL
                    + ": its entries are not per-tenant, so there is no tenant-scoped key to evict."
                    + " Use getCache(name).evict(key).");
        }
        if (orgId == null) {
            throw new IllegalArgumentException(
                    "evictForTenant needs the organization whose entry is being dropped, and got null");
        }
        ((TwoLevelCache) getCache(cacheName)).evictForTenant(orgId, key);
    }

    /** L1-only eviction on invalidation messages from other instances. */
    void evictLocal(String cacheName, String key) {
        // Deliberately not getCache(): a message naming a cache this node has never touched (or, after a
        // rolling deploy, one this binary no longer declares) must be dropped, not turned into a startup
        // failure on the listener container's thread.
        TwoLevelCache cache = caches.get(cacheName);
        if (cache != null) {
            cache.evictLocal(key);
        }
    }
}
