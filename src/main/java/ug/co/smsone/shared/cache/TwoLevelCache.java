package ug.co.smsone.shared.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

/**
 * Caffeine-first cache with a shared Valkey/Redis second level. L2 failures degrade to L1-only
 * (a cache outage must never take the application down). Writes and evictions broadcast an
 * invalidation so other instances drop their stale L1 entries.
 */
class TwoLevelCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);
    private static final String JDK_IMMUTABLE_COLLECTION = "java.util.ImmutableCollections$";

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
                // The JSON round-trip strips the producer's immutability (an unmodifiable set comes
                // back a plain LinkedHashSet), and this one instance is then shared by every caller
                // on this node — one caller's mutation would poison the entry for all later reads.
                Object value = immutableView(shared.get());
                l1.put(key, value);
                return new SimpleValueWrapper(value);
            }
            return null;
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

    /**
     * Does NOT serialize concurrent loads, so {@code @Cacheable(sync = true)} would compile against
     * this cache but provide no stampede protection. No caller relies on it today; revisit (delegate
     * to Caffeine's per-key locking) before one does.
     */
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
        l1.put(key, value); // L1 keeps the caller's instance — only the L2 copy needs normalizing
        if (l2 != null) {
            try {
                l2.put(key, l2Value(value));
            } catch (RuntimeException e) {
                log.warn("L2 cache '{}' put failed, entry is L1-only: {}", name, e.getMessage());
            }
        }
        // regardless of L2 outcome: peers drop stale L1 and reload from L2-or-DB
        if (key instanceof String stringKey) {
            broadcast(stringKey);
        }
    }

    @Override
    public void evict(Object key) {
        // L2 strictly before L1: evicting L1 first opens a window where a concurrent reader misses
        // L1, refills it from the still-stale L2 AFTER this method returns — and the invalidation
        // listener skips our own broadcast, so the resurrected entry would live a full L1 TTL.
        boolean l2Evicted = true;
        if (l2 != null) {
            try {
                l2.evict(key);
            } catch (RuntimeException e) {
                // do NOT broadcast: peers would refill their L1 from the still-stale L2 entry.
                // Everyone stays equally stale until the L1/L2 TTLs expire.
                l2Evicted = false;
                log.warn("L2 cache '{}' evict failed — skipping invalidation broadcast: {}",
                        name, e.getMessage());
            }
        }
        l1.evict(key);
        if (l2Evicted) {
            // non-String keys can't be round-tripped through the text topic — clear peers' cache
            broadcast(key instanceof String stringKey ? stringKey : null);
        }
    }

    @Override
    public void clear() {
        // Same ordering rationale as evict(): L2 first, so a concurrent reader cannot repopulate L1
        // from entries this clear is about to remove.
        boolean l2Cleared = true;
        if (l2 != null) {
            try {
                l2.clear();
            } catch (RuntimeException e) {
                l2Cleared = false;
                log.warn("L2 cache '{}' clear failed — skipping invalidation broadcast: {}",
                        name, e.getMessage());
            }
        }
        l1.clear();
        if (l2Cleared) {
            broadcast(null);
        }
    }

    /** Drops the local L1 entry only — used when another instance broadcasts an invalidation. */
    void evictLocal(Object key) {
        if (key == null) {
            l1.clear();
        } else {
            l1.evict(key);
        }
    }

    /**
     * Jackson writes a root-level JDK immutable collection ({@code Set.of}, {@code List.of},
     * {@code Map.of}, {@code Collectors.toUnmodifiable*}) with NO type id, so it reads back as a raw
     * ArrayList/LinkedHashMap and the {@code @Cacheable} cast fails — only once L1 has expired, which
     * makes it look intermittent. Nested immutables and every other collection type keep their id, so
     * the copy is confined to the root. Costs one shallow copy per L2 write of such a value.
     */
    private static Object l2Value(Object value) {
        if (value == null || !value.getClass().getName().startsWith(JDK_IMMUTABLE_COLLECTION)) {
            return value;
        }
        if (value instanceof Set<?> set) {
            return new LinkedHashSet<>(set);
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>(map);
        }
        return value;
    }

    /** Views, not copies: the cached entry itself is never written through them. */
    private static Object immutableView(Object value) {
        if (value instanceof Set<?> set) {
            return Collections.unmodifiableSet(set);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list);
        }
        if (value instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap(map);
        }
        return value;
    }

    private void broadcast(Object key) {
        if (broadcaster != null) {
            broadcaster.publish(name, key == null ? null : key.toString());
        }
    }
}
