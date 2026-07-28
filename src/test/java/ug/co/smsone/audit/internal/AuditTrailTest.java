package ug.co.smsone.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import ug.co.smsone.organization.MembershipCreated;
import ug.co.smsone.settings.SettingChanged;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The audit module recording domain events into {@code audit_log}: a published event lands as exactly
 * one row (idempotent via {@code EventInbox}). Uses the Modulith {@link Scenario} so events are
 * published within a transaction the after-commit listeners actually observe.
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
class AuditTrailTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = AbstractIntegrationTest.POSTGRES;

    static {
        POSTGRES.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditRecorder recorder;

    @Test
    void recordsAnOrganizationMemberEvent(Scenario scenario) {
        UUID orgId = UUID.randomUUID();
        String subject = "kc-" + UUID.randomUUID();

        scenario.publish(new MembershipCreated(orgId, subject, "MEMBER", Instant.now()))
                .andWaitForStateChange(() -> count("organization.member_added", subject) > 0 ? Boolean.TRUE : null)
                .andVerify(ready -> {
                    assertThat(count("organization.member_added", subject)).isEqualTo(1);
                    assertThat(jdbc.queryForObject(
                            "select detail from audit_log where action = ? and target = ?",
                            String.class, "organization.member_added", subject)).isEqualTo("role=MEMBER");
                });
    }

    @Test
    void recordsASettingChange(Scenario scenario) {
        String key = "billing.mode-" + UUID.randomUUID();

        scenario.publish(new SettingChanged(key, "annual"))
                .andWaitForStateChange(() -> count("settings.changed", key) > 0 ? Boolean.TRUE : null)
                .andVerify(ready -> assertThat(count("settings.changed", key)).isEqualTo(1));
    }

    @Test
    void redeliveryOfTheSameEventIsRecordedOnce() {
        String target = "kc-" + UUID.randomUUID();
        String messageId = "dup-" + UUID.randomUUID();

        recorder.record(messageId, AuditEntry.of(null, "test.dup", target, null, Instant.now()));
        recorder.record(messageId, AuditEntry.of(null, "test.dup", target, null, Instant.now()));

        assertThat(count("test.dup", target)).isEqualTo(1);
    }

    private long count(String action, String target) {
        Long value = jdbc.queryForObject(
                "select count(*) from audit_log where action = ? and target = ?", Long.class, action, target);
        return value == null ? 0 : value;
    }
}
