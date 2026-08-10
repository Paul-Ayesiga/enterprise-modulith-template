package ug.co.smsone.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import ug.co.smsone.settings.internal.SettingService;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/** Two-level cache against REAL Valkey 8: hit/evict, L2 fallback, cross-instance invalidation. */
class ValkeyCacheIntegrationTest extends AbstractIntegrationTest {

    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource") // Testcontainers owns this lifecycle — see AbstractIntegrationTest.POSTGRES
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    @DynamicPropertySource
    static void cacheProperties(DynamicPropertyRegistry registry) {
        registry.add("app.cache.l2-enabled", () -> "true");
    }

    /**
     * The two scratch caches this class writes probe values into. {@code TwoLevelCacheManager} refuses
     * an undeclared name (ADR 0010 §3.5), and rightly — but a probe of the L2 SERIALIZATION contract is
     * not an application cache and has no business in {@code CacheRegistry}, where
     * {@code CacheDeclarationTest} would then demand production code use it. {@code standardPlus}
     * is the seam for exactly this, and it refuses to reclassify a name the application already
     * declares, so a test cannot quietly turn {@code org-permissions} global to make itself pass.
     *
     * <p>GLOBAL because these probes are about JSON round-tripping and nothing else; the tenant-scoping
     * half is {@code TenantCacheScopingTest}'s, where the keys are readable without Valkey.
     */
    @TestConfiguration
    static class ProbeCacheDeclarations {

        @Bean
        @Primary
        CacheRegistry probeCacheRegistry() {
            return CacheRegistry.standardPlus(Map.of(
                    "null-probe", CacheScope.GLOBAL,
                    "cache.typeprobe", CacheScope.GLOBAL));
        }
    }

    @Autowired
    private SettingService settingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CaffeineCacheManager caffeineCacheManager;

    @Autowired
    private TwoLevelCacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CacheInvalidationBroadcaster broadcaster;

    @Autowired
    private org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory;

    @Autowired
    private CacheProperties cacheProperties;

    @Test
    void cachesReadsAndEvictsOnWrite() {
        settingService.put("cache.probe", "v1", null);
        assertThat(settingService.valueOf("cache.probe")).isEqualTo("v1");

        // mutate behind the cache's back — the cached value must keep winning
        jdbcTemplate.update("update setting set setting_value = 'db-direct' where setting_key = 'cache.probe'");
        assertThat(settingService.valueOf("cache.probe")).isEqualTo("v1");

        // a write through the service evicts, so the next read sees fresh state
        settingService.put("cache.probe", "v2", null);
        assertThat(settingService.valueOf("cache.probe")).isEqualTo("v2");
    }

    @Test
    void l2ServesWhenL1IsGone() {
        settingService.put("cache.l2probe", "shared-value", null);
        assertThat(settingService.valueOf("cache.l2probe")).isEqualTo("shared-value");

        jdbcTemplate.update("update setting set setting_value = 'db-direct' where setting_key = 'cache.l2probe'");
        caffeineCacheManager.getCache(SettingService.VALUES_CACHE).clear(); // wipe L1 only

        // still the cached value: served from Valkey, not the database
        assertThat(settingService.valueOf("cache.l2probe")).isEqualTo("shared-value");
    }

    /**
     * Negative caching: a cached null must survive the L2 round-trip. Without NullValue support the
     * serialized sentinel failed type validation on every read — each read became a WARN + miss that
     * re-cached and re-broadcast, evicting peers' good entries (and {@code SettingService.valueOf}
     * caches {@code orElse(null)} on its documented hot path).
     */
    @Test
    void aCachedNullSurvivesAnL2OnlyRead() {
        Cache cache = cacheManager.getCache("null-probe");
        cache.put("missing", null);
        caffeineCacheManager.getCache("null-probe").clear(); // wipe L1 only — force the L2 read

        Cache.ValueWrapper wrapper = cache.get("missing");
        assertThat(wrapper).as("the null entry must still be a HIT from L2, not a miss").isNotNull();
        assertThat(wrapper.get()).isNull();
    }

    /**
     * Regression: L2 stores JSON, so without type information every collection came back as an
     * {@code ArrayList} and every object as a {@code LinkedHashMap} — a {@code @Cacheable} method
     * returning {@code Set<String>} then blew up with a ClassCastException inside the CGLIB proxy,
     * but only once L1 had expired (L1 holds the real object, which is why it looked intermittent).
     */
    @Test
    void nonScalarValuesKeepTheirTypeAcrossAnL2OnlyRead() {
        // Set.of/List.of/Map.of are the shapes that failed: Jackson gives a root-level JDK immutable
        // collection no type id at all. PermissionResolver.resolve returns exactly the first one.
        assertSurvivesL2RoundTrip("set", Set.of("org:read", "member:read"), Set.class);
        assertSurvivesL2RoundTrip("list", List.of("a", "b"), List.class);
        assertSurvivesL2RoundTrip("map", Map.of("k", "v"), Map.class);
        assertSurvivesL2RoundTrip("mutable-set", new LinkedHashSet<>(Set.of("x")), Set.class);
        assertSurvivesL2RoundTrip("record", new CachedShape(Set.of("org:read"), 2), CachedShape.class);
    }

    /**
     * The SCALAR shape, and it is the mirror image of the test above: a scalar does NOT keep its type.
     * A JSON string has no property to hang a type id on, so a cached {@link java.util.UUID} comes back
     * as a {@code String} — and a {@code @Cacheable} method declared to return {@code UUID} would then
     * ClassCastException inside the CGLIB proxy, on every authenticated request, but only once L1 had
     * expired.
     *
     * <p>This is asserted rather than fixed because it is the constraint the edge resolvers were
     * designed around, and the design is only readable if the constraint is written down: {@code
     * identity.internal.PersonResolutionCache} and {@code organization.internal.OrgResolutionCache}
     * cache the id AS TEXT for exactly this reason. If a later change ever makes scalars carry their
     * type, this test fails and points at the two classes that may then be simplified.
     */
    @Test
    void aScalarLosesItsTypeAcrossL2WhichIsWhyTheEdgeResolversCacheText() {
        java.util.UUID id = java.util.UUID.randomUUID();
        Cache cache = cacheManager.getCache("cache.typeprobe");
        cache.put("bare-uuid", id);
        caffeineCacheManager.getCache("cache.typeprobe").clear(); // wipe L1 — force the L2 round-trip

        assertThat(cache.get("bare-uuid").get())
                .as("a bare UUID reconstructs as a String — cache the text, never the UUID")
                .isInstanceOf(String.class)
                .isEqualTo(id.toString());

        // The shape the resolvers actually cache. It survives BECAUSE String is what a JSON scalar
        // reconstructs as whether or not a type id was written.
        assertSurvivesL2RoundTrip("uuid-text", id.toString(), String.class);
    }

    /** A record with a collection field — the other shape modules cache. */
    record CachedShape(Set<String> codes, int count) {
    }

    private void assertSurvivesL2RoundTrip(String key, Object value, Class<?> expectedType) {
        Cache cache = cacheManager.getCache("cache.typeprobe");
        cache.put(key, value);

        caffeineCacheManager.getCache("cache.typeprobe").clear(); // wipe L1 — force the L2 round-trip

        Cache.ValueWrapper wrapper = cache.get(key);
        assertThat(wrapper).as("%s served from L2", key).isNotNull();
        assertThat(wrapper.get()).as("%s keeps its type", key).isInstanceOf(expectedType).isEqualTo(value);
    }

    @Test
    void foreignInvalidationBroadcastEvictsL1() throws Exception {
        settingService.put("cache.bcast", "original", null);
        assertThat(settingService.valueOf("cache.bcast")).isEqualTo("original");
        assertThat(caffeineCacheManager.getCache(SettingService.VALUES_CACHE).get("cache.bcast")).isNotNull();

        // simulate ANOTHER instance broadcasting an eviction (different instance id)
        redisTemplate.convertAndSend(broadcaster.topic(),
                "some-other-instance\n" + SettingService.VALUES_CACHE + "\ncache.bcast");

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(
                        caffeineCacheManager.getCache(SettingService.VALUES_CACHE).get("cache.bcast"))
                        .as("L1 entry evicted by foreign broadcast")
                        .isNull());
    }

    /**
     * <b>ADR 0010 §6 hop 2→3, proved rather than declared: two deployments on ONE Valkey do not see each
     * other's entries.</b> The unit gate ({@code shared.deployment.DeploymentNamespaceTest}) pins the key
     * strings; this pins the thing that actually matters, which is that the two managers are built by
     * the SAME production code path and still cannot reach one another.
     *
     * <p>The direction that would be a defect rather than a nuisance is the second half: the extracted
     * deployment must not READ the platform's value. A shared namespace does not merely over-evict — it
     * answers a question with another installation's data, and for {@code org-permissions} that is an
     * authorization decision computed against a database this deployment has never seen.
     */
    @Test
    void twoDeploymentsSharingOneValkeyCannotReadOrEvictEachOthersEntries() {
        String key = "isolation-" + java.util.UUID.randomUUID();
        Cache platform = l2Of(new ug.co.smsone.shared.deployment.DeploymentIdentity(
                ug.co.smsone.shared.deployment.DeploymentIdentity.PLATFORM));
        Cache extracted = l2Of(new ug.co.smsone.shared.deployment.DeploymentIdentity("acme"));

        platform.put(key, "the platform's answer");
        assertThat(extracted.get(key))
                .as("the extracted deployment must MISS — a hit here is the platform's cached value"
                        + " answering another installation's read")
                .isNull();

        extracted.put(key, "the extracted deployment's answer");
        assertThat(platform.get(key).get()).isEqualTo("the platform's answer");
        assertThat(extracted.get(key).get()).isEqualTo("the extracted deployment's answer");

        extracted.evict(key);
        assertThat(platform.get(key))
                .as("one deployment's eviction must not clear the other's entry")
                .isNotNull();
    }

    /**
     * The same separation for the invalidation topic. It carries no data, so sharing it could only
     * over-evict — but a permanent, unattributable hit-rate hole in a deployment where nothing is being
     * written is exactly the kind of thing nobody ever traces back to a shared Valkey.
     */
    @Test
    void anotherDeploymentsInvalidationBroadcastIsNotHeard() throws Exception {
        settingService.put("cache.foreign-topic", "original", null);
        assertThat(settingService.valueOf("cache.foreign-topic")).isEqualTo("original");
        assertThat(caffeineCacheManager.getCache(SettingService.VALUES_CACHE)
                .get("cache.foreign-topic")).isNotNull();

        String otherDeploymentsTopic = new ug.co.smsone.shared.deployment.DeploymentIdentity("acme")
                .valkeyKey(CacheInvalidationBroadcaster.TOPIC);
        assertThat(otherDeploymentsTopic).isNotEqualTo(broadcaster.topic());
        redisTemplate.convertAndSend(otherDeploymentsTopic,
                "some-other-instance\n" + SettingService.VALUES_CACHE + "\ncache.foreign-topic");

        // A negative across a network hop needs a settle: the positive case above completes well inside
        // five seconds, so a second of quiet is evidence rather than optimism.
        Thread.sleep(1000);
        assertThat(caffeineCacheManager.getCache(SettingService.VALUES_CACHE).get("cache.foreign-topic"))
                .as("this deployment is not subscribed to the other's topic")
                .isNotNull();
    }

    /**
     * One deployment's L2 cache, built through the production {@code @Bean} method so the test cannot
     * pass by re-implementing the prefix it is checking. {@code afterPropertiesSet} is what a container
     * would call — without it {@code AbstractCacheManager} is not "dynamic" and hands back null.
     */
    private Cache l2Of(ug.co.smsone.shared.deployment.DeploymentIdentity deployment) {
        org.springframework.data.redis.cache.RedisCacheManager manager =
                new CacheConfig().redisCacheManager(connectionFactory, cacheProperties, deployment);
        manager.afterPropertiesSet();
        return manager.getCache("cache.typeprobe");
    }
}
