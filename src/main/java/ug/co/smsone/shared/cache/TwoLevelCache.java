package ug.co.smsone.shared.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * Caffeine-first cache with a shared Valkey/Redis second level. L2 failures degrade to L1-only
 * (a cache outage must never take the application down). Writes and evictions broadcast an
 * invalidation so other instances drop their stale L1 entries.
 *
 * <h2>Tenancy (ADR 0010 §3.5)</h2>
 *
 * <p>Every cache carries the {@link CacheScope} {@link CacheRegistry} declared for it. A
 * {@link CacheScope#TENANT} cache prefixes {@code t_<32hex>|} — the tenant's own schema name, derived
 * from {@code organization.id} — into the key of <strong>both</strong> levels, and refuses to answer at
 * all when the thread has declared no tenant. A {@link CacheScope#GLOBAL} cache is untouched.
 *
 * <p><b>Both levels, or neither is worth doing.</b> Prefixing L1 only would leave every node's Valkey
 * entry shared; prefixing L2 only would leave the in-process entry shared for its whole 60 s TTL. The
 * scoping therefore happens once, at the top of each public method, and both {@code l1} and {@code l2}
 * see the same already-scoped key. The invalidation broadcast carries the scoped key too — the peer
 * that receives it has no tenant axis of its own to re-derive one from, which is exactly why
 * {@link #evictLocal} must never scope again.
 *
 * <p><b>Absent throws; it does not fall back.</b> A shared fallback key is the leak, and it is the
 * quiet kind: the first caller with no axis publishes an entry every other tenant then reads. So the
 * {@link Tenant#ABSENT} and {@link Tenant.Platform} states raise here rather than inventing a
 * namespace. {@link #clear()} and {@link #evictLocal} are the deliberate exceptions — neither names a
 * key, both are strictly over-eviction, and both run on threads (an {@code @ApplicationModuleListener}
 * after commit, the Valkey listener container) that have no axis and have no business inventing one.
 *
 * <p><b>The prefix is the AXIS's tenant, not the entry's.</b> The cache sees a key, never the org a
 * value is about, so a caller that pins one tenant and asks about another namespaces its entries under
 * the pin. That is not a transitional wart: ADR 0010 §3.4 fixes the background fan-out at one axis per
 * HOME rather than one per tenant (§1 and §5 measure why per-tenant is unshippable at 5,000 orgs), and
 * {@code TenantHome.pool()} is that axis for the shared schema — a uuid naming no organization, covering
 * every unpromoted tenant there is. So there is permanently a class of caller that resolves many orgs
 * under one prefix, {@code ExchangeScheduleFiringJob} being the one doing it today. Two consequences,
 * both load-bearing:
 *
 * <ul>
 *   <li>{@code org-permissions} keeps the organization inside its key. Under a home's axis that
 *       component is the ONLY thing separating two tenants' answers, and the answer is an authorization
 *       decision — see {@code PermissionResolver}'s note, which closes the question for good.</li>
 *   <li>{@code org-entitlements} does not need one, because {@code TenantCacheKeys.forThisTenant}
 *       <em>throws</em> unless the axis is the org being asked about — the option a per-member cache
 *       does not have.</li>
 * </ul>
 *
 * <p>The eviction side follows from the same fact: an entry written under a home's axis cannot be
 * reached by a key an outside caller can name, so every eviction of {@code org-permissions} is a
 * {@link #clear()} that spans all prefixes. {@link TenantPromotionCaches} is where that is written down
 * per cache, for the one event that invalidates answers without changing any row: a promotion.
 */
class TwoLevelCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);
    private static final String JDK_IMMUTABLE_COLLECTION = "java.util.ImmutableCollections$";

    /**
     * Separates the tenant from the key. '|' rather than ':' because the keys it is joined to already
     * use ':' as their own separator ({@code org-permissions}) and an issuer URL is full of them.
     */
    private static final String TENANT_SEPARATOR = "|";

    private final String name;
    private final CacheScope scope;
    private final Cache l1;
    private final Cache l2;
    private final CacheInvalidationBroadcaster broadcaster;
    // Pre-registered: get() is on the request hot path, so the counters are looked up once, not per hit.
    private final io.micrometer.core.instrument.Counter l1Hits;
    private final io.micrometer.core.instrument.Counter l2Hits;
    private final io.micrometer.core.instrument.Counter misses;
    // Per-key striped locks so @Cacheable(sync=true) loads a cold key ONCE per node, not N times.
    private static final int LOAD_STRIPES = 256;
    private final Object[] loadLocks = new Object[LOAD_STRIPES];

    TwoLevelCache(String name, CacheScope scope, Cache l1, Cache l2, CacheInvalidationBroadcaster broadcaster,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.name = name;
        this.scope = scope;
        this.l1 = l1;
        this.l2 = l2;
        this.broadcaster = broadcaster;
        this.l1Hits = requestCounter(meters, "l1_hit");
        this.l2Hits = requestCounter(meters, "l2_hit");
        this.misses = requestCounter(meters, "miss");
        for (int i = 0; i < LOAD_STRIPES; i++) {
            loadLocks[i] = new Object();
        }
    }

    private io.micrometer.core.instrument.Counter requestCounter(
            io.micrometer.core.instrument.MeterRegistry meters, String result) {
        return io.micrometer.core.instrument.Counter.builder("smsone.cache.requests")
                .description("Two-level cache lookups by outcome (a degraded L2 read counts as a miss)")
                .tag("cache", name)
                .tag("result", result)
                .register(meters);
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
        return lookup(scopedKey(key));
    }

    private ValueWrapper lookup(Object key) {
        ValueWrapper local = l1.get(key);
        if (local != null) {
            l1Hits.increment();
            return local;
        }
        if (l2 == null) {
            misses.increment();
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
                l2Hits.increment();
                return new SimpleValueWrapper(value);
            }
            misses.increment();
            return null;
        } catch (RuntimeException e) {
            log.warn("L2 cache '{}' get failed, degrading to L1-only: {}", name, e.getMessage());
            misses.increment();
            return null;
        }
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        return wrapper == null ? null : type.cast(wrapper.get());
    }

    /**
     * Stampede-protected load for {@code @Cacheable(sync = true)}: a cold key is computed ONCE per
     * node while concurrent callers wait on a striped lock and then read the freshly-cached value.
     * Striped (not per-key) so the lock table is bounded — a hash collision only means two unrelated
     * keys occasionally serialize, never incorrectness. Cross-node, L2 absorbs the rest: the first
     * node to load publishes to L2, so a peer node's own cold read hits L2 rather than reloading.
     *
     * <p>The stripe is taken from the SCOPED key, so two tenants loading "the same" cold key contend
     * only by hash collision, exactly as two unrelated keys do.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object scoped = scopedKey(key);
        ValueWrapper wrapper = lookup(scoped);
        if (wrapper != null) {
            return (T) wrapper.get();
        }
        synchronized (loadLocks[Math.floorMod(scoped.hashCode(), LOAD_STRIPES)]) {
            // Double-check under the lock: a peer that just held it has populated L1 (put writes L1
            // synchronously), so we read its value instead of running the loader a second time.
            ValueWrapper loaded = l1.get(scoped);
            if (loaded != null) {
                l1Hits.increment();
                return (T) loaded.get();
            }
            try {
                T value = valueLoader.call();
                store(scoped, value);
                return value;
            } catch (Exception e) {
                throw new ValueRetrievalException(key, valueLoader, e);
            }
        }
    }

    @Override
    public void put(Object key, Object value) {
        store(scopedKey(key), value);
    }

    private void store(Object key, Object value) {
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
        evictScoped(scopedKey(key));
    }

    /**
     * Evicts one TENANT entry for an organization the current thread is NOT pinned to — the shape an
     * after-commit listener needs, because {@code @ApplicationModuleListener} runs in its own
     * transaction on a pooled thread with no axis, and {@link TenantContext} refuses to pin one there
     * (correctly: the connection is already bound). Naming the org explicitly is the honest form of
     * that call, and it is the only way to reach another tenant's key.
     */
    void evictForTenant(UUID orgId, Object key) {
        evictScoped(tenantPrefix(orgId) + key);
    }

    private void evictScoped(Object key) {
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

    /**
     * Drops every entry of every tenant. Deliberately needs no axis: it names no key, it can only
     * over-evict, and its callers have none — after-commit listeners, and {@link TenantPromotionCaches}
     * on a promotion's placement flip. Coarse but correct beats precise and wrong; and for
     * {@code org-permissions} it is also the only eviction that reaches an entry written under a HOME's
     * axis (see the class note), whose prefix no outside caller can name.
     */
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

    /**
     * Drops the local L1 entry only — used when another instance broadcasts an invalidation. The key
     * arrives ALREADY scoped (the broadcaster published what it evicted), and the listener thread has
     * no tenant axis, so scoping it again would both double the prefix and throw.
     */
    void evictLocal(Object key) {
        if (key == null) {
            l1.clear();
        } else {
            l1.evict(key);
        }
    }

    /**
     * The key as both levels store it: untouched for a GLOBAL cache, tenant-prefixed for a TENANT one.
     *
     * @throws IllegalStateException on a TENANT cache when the thread has declared no tenant — see the
     *     class note on why this is not a fallback.
     */
    private Object scopedKey(Object key) {
        return scope == CacheScope.TENANT ? currentTenantPrefix() + key : key;
    }

    private String currentTenantPrefix() {
        Tenant tenant = TenantContext.current();
        if (tenant instanceof Tenant.Org org) {
            return tenantPrefix(org.orgId());
        }
        throw new IllegalStateException("Cache '" + name + "' is declared " + CacheScope.TENANT
                + " and this thread has no tenant axis (" + tenant.getClass().getSimpleName()
                + "). There is no shared key to fall back to: one would serve the first caller's rows to"
                + " every other tenant. Pin the organization with TenantContext before the read — outside"
                + " any transaction — or, to reach one named tenant's entry from a thread that cannot pin"
                + " (an after-commit listener), use TwoLevelCacheManager.evictForTenant(...).");
    }

    /** {@code t_<32hex>|} — the tenant's own schema name, so a cache key and a search_path agree. */
    private static String tenantPrefix(UUID orgId) {
        return TenantSchemas.siloSchema(orgId) + TENANT_SEPARATOR;
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
