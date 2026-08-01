package ug.co.smsone.scheduler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.TestPropertySource;
import ug.co.smsone.settings.SettingChanged;
import ug.co.smsone.settings.internal.SettingService;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

@TestPropertySource(properties = "app.scheduler.event-retention=PT0S")
@Import(EventPurgeJobIntegrationTest.SettingChangedProbe.class)
class EventPurgeJobIntegrationTest extends AbstractIntegrationTest {

    /** Registry rows only exist for consumed events — this probe is the consumer. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SettingChangedProbe {

        @ApplicationModuleListener
        void on(SettingChanged event) {
            // consuming is enough: completion is what the purge job sweeps
        }
    }

    @Autowired
    private SettingService settingService;

    @Autowired
    private EventPublicationPurgeJob purgeJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void purgesCompletedPublications() {
        // produce a completed publication through the real event flow
        settingService.put("purge.probe", "x", null);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(countCompleted()).isGreaterThanOrEqualTo(1));

        purgeJob.purgeCompletedPublications();

        assertThat(countCompleted()).isZero();
    }

    private int countCompleted() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from event_publication where completion_date is not null", Integer.class);
        return count == null ? 0 : count;
    }
}
