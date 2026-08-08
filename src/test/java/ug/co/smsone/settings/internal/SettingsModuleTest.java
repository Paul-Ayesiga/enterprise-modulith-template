package ug.co.smsone.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ug.co.smsone.settings.SettingChanged;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.TenantAxisExtension;

/**
 * Modulith slice test: boots the settings module (+ its dependencies) against real Postgres.
 *
 * <p>{@code @ExtendWith(TenantAxisExtension.class)} rather than inheritance: this class bootstraps its
 * own slice instead of extending {@code AbstractIntegrationTest} (it only borrows the container), so it
 * would otherwise run with no tenant axis and every borrow would land in the empty {@code no_tenant}
 * schema. Same extension, same bracket — ADR 0010 §3.4.
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
@ExtendWith(TenantAxisExtension.class)
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
        // created_by is a uuid holding person.id, and NULL is the schema's way of saying "no person did
        // this write" — a job, a startup task, a slice test off a request thread (JpaAuditingConfig).
        // The "system" sentinel this once asserted was deleted rather than translated: there is no system
        // person row to point at. The attributed case is pinned by SubjectAttributionTest, which drives
        // this same service over HTTP and asserts created_by equals the caller's person.id.
        assertThat(saved.getCreatedBy()).as("no authenticated person behind a slice-test write").isNull();

        var updated = settingService.put("audit.probe", "v2", null);
        assertThat(updated.getValue()).isEqualTo("v2");
        assertThat(updated.getVersion()).isGreaterThan(saved.getVersion());
    }
}
