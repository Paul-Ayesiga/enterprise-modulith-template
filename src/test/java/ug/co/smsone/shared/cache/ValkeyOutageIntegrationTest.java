package ug.co.smsone.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import ug.co.smsone.settings.internal.SettingService;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The degrade guarantee under a REAL mid-flight Valkey outage: L1 keeps serving, misses fall
 * through to the DB quickly (tight Lettuce timeouts — not the 60s default), writes still succeed.
 * This class owns its container because it kills it.
 */
class ValkeyOutageIntegrationTest extends AbstractIntegrationTest {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> DOOMED_VALKEY =
            new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    static {
        DOOMED_VALKEY.start();
    }

    @DynamicPropertySource
    static void cacheProperties(DynamicPropertyRegistry registry) {
        registry.add("app.cache.l2-enabled", () -> "true");
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("spring.data.redis.connect-timeout", () -> "2s");
    }

    @Autowired
    private SettingService settingService;

    @Test
    void survivesValkeyDyingMidFlight() {
        settingService.put("outage.probe", "cached-value", null);
        assertThat(settingService.valueOf("outage.probe")).isEqualTo("cached-value");

        DOOMED_VALKEY.stop();

        // L1 still serves the hot entry
        assertThat(settingService.valueOf("outage.probe")).isEqualTo("cached-value");

        // a cold read degrades to the DB and completes fast (not Lettuce's 60s default)
        Instant start = Instant.now();
        assertThat(settingService.valueOf("outage.cold")).isNull();
        assertThat(Duration.between(start, Instant.now()))
                .as("degraded read must fail fast, not hang on Lettuce defaults")
                .isLessThan(Duration.ofSeconds(10));

        // writes still work while L2 is down
        settingService.put("outage.write", "still-works", null);
        assertThat(settingService.valueOf("outage.write")).isEqualTo("still-works");
    }
}
