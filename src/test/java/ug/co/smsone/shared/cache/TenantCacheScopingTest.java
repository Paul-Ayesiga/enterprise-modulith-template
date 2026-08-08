package ug.co.smsone.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * The ADR 0010 §3.5 gate on the cache itself: <b>a TENANT cache separates tenants in BOTH levels, and
 * refuses to answer at all when nobody has said which tenant is asking.</b>
 *
 * <p>The refusal is the assertion that matters. Every other outcome for "a tenant-scoped read with no
 * tenant" is a shared key — one namespace that the first caller populates and every later tenant then
 * reads — and it fails in the quietest possible way: no exception, no wrong-schema error, just another
 * organization's rows, for as long as the entry lives. A throw converts the worst failure this design
 * can produce into one that shows up on the first request that gets it wrong.
 *
 * <p>Two levels, not one, is the other half. L1 is per-node with a 60 s TTL and L2 is shared across
 * every node for ten minutes, so prefixing either alone leaves the other leaking — and leaves it
 * leaking on a schedule that makes the bug look intermittent. Both maps are inspected directly here
 * rather than through the {@code Cache} facade, because the facade is the thing under test.
 *
 * <p>Pure unit test: two {@code ConcurrentMapCache}s standing in for Caffeine and Valkey, so the keys
 * are readable and no container is needed. What is real is {@link TenantContext}, {@link CacheRegistry}
 * and {@link TwoLevelCacheManager} — the three things the guarantee is made of.
 */
class TenantCacheScopingTest {

    private static final String TENANT_CACHE = "org-permissions";
    private static final String GLOBAL_CACHE = "setting-values";

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    private ConcurrentMapCacheManager l1;
    private ConcurrentMapCacheManager l2;
    private TwoLevelCacheManager caches;

    @BeforeEach
    void setUp() {
        l1 = new ConcurrentMapCacheManager();
        l2 = new ConcurrentMapCacheManager();
        caches = new TwoLevelCacheManager(CacheRegistry.standard(), l1, l2, null, new SimpleMeterRegistry());
    }

    @AfterEach
    void clearAxis() {
        TenantContext.clear();
    }

    /** The gate, stated plainly: no axis, no answer — and specifically no shared-key answer. */
    @Test
    void aTenantCacheLookupWithNoAxisThrowsRatherThanFallingBackToASharedKey() {
        Cache cache = caches.getCache(TENANT_CACHE);

        assertThatIllegalStateException().isThrownBy(() -> cache.get("k"))
                .withMessageContaining("no tenant axis")
                .withMessageContaining("no shared key to fall back to");
        assertThatIllegalStateException().isThrownBy(() -> cache.put("k", Set.of("org:read")));
        assertThatIllegalStateException().isThrownBy(() -> cache.evict("k"));
        assertThatIllegalStateException().isThrownBy(() -> cache.get("k", () -> Set.of("org:read")));

        assertThat(keys(l1)).as("nothing was written under any key at all").isEmpty();
        assertThat(keys(l2)).isEmpty();
    }

    /**
     * PLATFORM is an axis, but it is not a tenant. It reaches the platform schema, where none of the
     * tenant-tier tables behind these caches even exist — so a platform-axis entry could only ever be a
     * value some tenant-pinned caller left behind under a name nobody owns.
     */
    @Test
    void thePlatformAxisIsNotATenantEither() {
        TenantContext.setPlatform();
        Cache cache = caches.getCache(TENANT_CACHE);

        assertThatIllegalStateException().isThrownBy(() -> cache.get("k")).withMessageContaining("Platform");
    }

    /** L1 AND L2. One of them unprefixed is the whole leak, on that level's TTL. */
    @Test
    void theTenantIsPrefixedIntoTheKeyAtBothLevels() {
        TenantContext.set(orgA);
        caches.getCache(TENANT_CACHE).put("k", Set.of("org:read"));

        String expected = TenantSchemas.siloSchema(orgA) + "|k";
        assertThat(keys(l1)).as("L1, the in-process level").containsExactly(expected);
        assertThat(keys(l2)).as("L2, the level shared by every node").containsExactly(expected);
    }

    @Test
    void oneTenantsEntryIsInvisibleToAnother() {
        TenantContext.set(orgA);
        caches.getCache(TENANT_CACHE).put("k", Set.of("org:read"));

        TenantContext.set(orgB);
        assertThat(caches.getCache(TENANT_CACHE).get("k"))
                .as("the other tenant's value must not be reachable under the same key")
                .isNull();

        caches.getCache(TENANT_CACHE).put("k", Set.of("member:read"));
        TenantContext.set(orgA);
        assertThat(caches.getCache(TENANT_CACHE).get("k").get())
                .as("and writing it must not have overwritten the first tenant's")
                .isEqualTo(Set.of("org:read"));
        assertThat(keys(l1)).hasSize(2);
    }

    /** A GLOBAL cache is untouched: same key, same entry, whoever is (or is not) asking. */
    @Test
    void aGlobalCacheIsIndifferentToTheAxis() {
        caches.getCache(GLOBAL_CACHE).put("smtp.host", "mail.example.org");

        TenantContext.set(orgA);
        assertThat(caches.getCache(GLOBAL_CACHE).get("smtp.host").get()).isEqualTo("mail.example.org");
        TenantContext.set(orgB);
        assertThat(caches.getCache(GLOBAL_CACHE).get("smtp.host").get()).isEqualTo("mail.example.org");
        assertThat(keys(l1, GLOBAL_CACHE)).containsExactly("smtp.host");
    }

    /** The other half of the registry: a name nobody classified is never handed out. */
    @Test
    void anUndeclaredCacheCannotBeObtainedAtAll() {
        assertThatIllegalArgumentException().isThrownBy(() -> caches.getCache("org-tickets"))
                .withMessageContaining("not declared in CacheRegistry")
                .withMessageContaining("org-permissions"); // the message lists what IS declared
    }

    /**
     * The sanctioned way to reach one tenant's key from a thread that has no axis and cannot take one —
     * an {@code @ApplicationModuleListener} after commit, which runs inside its own transaction where
     * {@code TenantContext} refuses to pin.
     */
    @Test
    void evictForTenantReachesANamedTenantsEntryWithNoAxis() {
        TenantContext.set(orgA);
        caches.getCache("org-entitlements").put(TenantCacheKeys.wholeTenant(), java.util.Map.of("members.max", 5L));
        TenantContext.clear();

        caches.evictForTenant("org-entitlements", orgA, TenantCacheKeys.wholeTenant());

        assertThat(keys(l1, "org-entitlements")).isEmpty();
        assertThat(keys(l2, "org-entitlements")).isEmpty();
    }

    @Test
    void evictForTenantRefusesAGlobalCacheBecauseItHasNoPerTenantKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> caches.evictForTenant(GLOBAL_CACHE, orgA, "smtp.host"))
                .withMessageContaining("GLOBAL");
    }

    /**
     * {@code clear()} names no key and can only over-evict, so it stays available with no axis — which
     * is what the permission evictors need, running after commit on a pooled thread.
     */
    @Test
    void clearNeedsNoAxisBecauseItNamesNoKey() {
        TenantContext.set(orgA);
        caches.getCache(TENANT_CACHE).put("k", Set.of("org:read"));
        TenantContext.clear();

        assertThatCode(() -> caches.getCache(TENANT_CACHE).clear()).doesNotThrowAnyException();
        assertThat(keys(l1)).isEmpty();
        assertThat(keys(l2)).isEmpty();
    }

    /**
     * The key-side guard that lets {@code org-entitlements} drop its hand-rolled organization: with the
     * tenant coming from the axis, asking about a DIFFERENT organization would file the answer under the
     * wrong prefix, and a cache hit runs no query so nothing else would notice.
     */
    @Test
    void aOneEntryPerTenantKeyRefusesAnOrganizationThatIsNotTheAxis() {
        TenantContext.set(orgA);
        assertThat(TenantCacheKeys.forThisTenant(orgA)).isEqualTo(TenantCacheKeys.wholeTenant());

        assertThatIllegalStateException().isThrownBy(() -> TenantCacheKeys.forThisTenant(orgB))
                .withMessageContaining(orgB.toString())
                .withMessageContaining(orgA.toString());

        TenantContext.clear();
        assertThatIllegalStateException().isThrownBy(() -> TenantCacheKeys.forThisTenant(orgA))
                .withMessageContaining("ABSENT");
    }

    private Set<Object> keys(ConcurrentMapCacheManager level) {
        return keys(level, TENANT_CACHE);
    }

    private Set<Object> keys(ConcurrentMapCacheManager level, String cacheName) {
        return ((ConcurrentMapCache) level.getCache(cacheName)).getNativeCache().keySet();
    }
}
