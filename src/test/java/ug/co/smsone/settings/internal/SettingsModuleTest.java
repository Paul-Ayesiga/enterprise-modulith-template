package ug.co.smsone.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ug.co.smsone.settings.SettingChanged;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/** Modulith slice test: boots the settings module (+ its dependencies) against real Postgres. */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
class SettingsModuleTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = AbstractIntegrationTest.POSTGRES;

    @Autowired
    private SettingService settingService;

    @Test
    void upsertPublishesSettingChangedThroughTheRegistry(Scenario scenario) {
        scenario.stimulate(() -> settingService.put("branding.title", "SMSOne", "Product display name"))
                .andWaitForEventOfType(SettingChanged.class)
                .matching(event -> event.key().equals("branding.title"))
                .toArriveAndVerify(event -> assertThat(event.value()).isEqualTo("SMSOne"));
    }

    @Test
    void auditsAndVersionsEntities() {
        var saved = settingService.put("audit.probe", "v1", null);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("system");

        var updated = settingService.put("audit.probe", "v2", null);
        assertThat(updated.getValue()).isEqualTo("v2");
        assertThat(updated.getVersion()).isGreaterThan(saved.getVersion());
    }
}
