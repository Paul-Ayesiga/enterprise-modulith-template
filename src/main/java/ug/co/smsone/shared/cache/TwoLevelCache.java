package ug.co.smsone.shared.cache;

import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

/**
 * Caffeine-first cache with a shared Valkey/Redis second level. L2 failures degrade to L1-only
 * (a cache outage must never take the application down). Writes and evictions broadcast an
 * invalidation so other instances drop their stale L1 entries.
 */
class TwoLevelCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    private final String name;
    private final Cache l1;
    private final Cache l2;
    private final CacheInvalidationBroadcaster broadcaster;

    TwoLevelCache(String name, Cache l1, Cache l2, CacheInvalidationBroadcaster broadcaster) {
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
        this.broadcaster = broadcaster;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return l1.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper local = l1.get(key);
        if (local != null) {
            return local;
        }
        if (l2 == null) {
            return null;
        }
        try {
            ValueWrapper shared = l2.get(key);
            if (shared != null) {
                l1.put(key, shared.get());
            }
            return shared;
        } catch (RuntimeException e) {
            log.warn("L2 cache '{}' get failed, degrading to L1-only: {}", name, e.getMessage());
            return null;
        }
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        return wrapper == null ? null : type.cast(wrapper.get());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
    }

    @Override
    public void put(Object key, Object value) {
        l1.put(key, value);
        if (l2 != null) {
            try {
                l2.put(key, value);
            } catch (RuntimeException e) {
                log.warn("L2 cache '{}' put failed, entry is L1-only: {}", name, e.getMessage());
            }
        }
        broadcast(key);
    }

    @Override
    public void evict(Object key) {
        l1.evict(key);
        if (l2 != null) {
            try {
                l2.evict(key);
            } catch (RuntimeException e) {
                log.warn("L2 cache '{}' evict failed: {}", name, e.getMessage());
            }
        }
        broadcast(key);
    }

    @Override
    public void clear() {
        l1.clear();
        if (l2 != null) {
            try {
                l2.clear();
            } catch (RuntimeException e) {
                log.warn("L2 cache '{}' clear failed: {}", name, e.getMessage());
            }
        }
        broadcast(null);
    }

    /** Drops the local L1 entry only — used when another instance broadcasts an invalidation. */
    void evictLocal(Object key) {
        if (key == null) {
            l1.clear();
        } else {
            l1.evict(key);
        }
    }

    private void broadcast(Object key) {
        if (broadcaster != null) {
            broadcaster.publish(name, key == null ? null : key.toString());
        }
    }
}
