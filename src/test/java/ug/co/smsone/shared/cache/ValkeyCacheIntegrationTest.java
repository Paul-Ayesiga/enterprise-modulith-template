package ug.co.smsone.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.caffeine.CaffeineCacheManager;
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
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    @DynamicPropertySource
    static void cacheProperties(DynamicPropertyRegistry registry) {
        registry.add("app.cache.l2-enabled", () -> "true");
    }

    @Autowired
    private SettingService settingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CaffeineCacheManager caffeineCacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

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

    @Test
    void foreignInvalidationBroadcastEvictsL1() throws Exception {
        settingService.put("cache.bcast", "original", null);
        assertThat(settingService.valueOf("cache.bcast")).isEqualTo("original");
        assertThat(caffeineCacheManager.getCache(SettingService.VALUES_CACHE).get("cache.bcast")).isNotNull();

        // simulate ANOTHER instance broadcasting an eviction (different instance id)
        redisTemplate.convertAndSend(CacheInvalidationBroadcaster.TOPIC,
                "some-other-instance\n" + SettingService.VALUES_CACHE + "\ncache.bcast");

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && caffeineCacheManager.getCache(SettingService.VALUES_CACHE).get("cache.bcast") != null) {
            Thread.sleep(50);
        }
        assertThat(caffeineCacheManager.getCache(SettingService.VALUES_CACHE).get("cache.bcast"))
                .as("L1 entry evicted by foreign broadcast")
                .isNull();
    }
}
