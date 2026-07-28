package ug.co.smsone.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ug.co.smsone.settings.internal.SettingService;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Audit capture through a real service: a change records who (the acting principal), what, and the
 * from→to state — atomically with the change. Also proves the actor is null for system-triggered
 * changes (no security context).
 */
class AuditRecordingTest extends AbstractIntegrationTest {

    @Autowired
    private SettingService settings;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void settingChangeRecordsWhoWhatAndFromToState() {
        String key = "billing.mode-" + UUID.randomUUID();
        authenticateAs("admin-alice");

        settings.put(key, "monthly", "Billing cadence");
        Map<String, Object> first = row(key, "monthly");
        assertThat(first.get("actor")).isEqualTo("admin-alice");
        assertThat(first.get("from_state")).isNull();
        assertThat(first.get("to_state")).isEqualTo("monthly");
        assertThat(first.get("action")).isEqualTo("settings.changed");

        settings.put(key, "annual", "Billing cadence");
        Map<String, Object> second = row(key, "annual");
        assertThat(second.get("from_state")).isEqualTo("monthly"); // the prior value
        assertThat(second.get("to_state")).isEqualTo("annual");
        assertThat(second.get("actor")).isEqualTo("admin-alice");
    }

    @Test
    void systemTriggeredChangeHasNoActor() {
        SecurityContextHolder.clearContext(); // no authenticated principal
        String target = "sys-" + UUID.randomUUID();

        auditLog.record("test.system", null, target, "off", "on");

        assertThat(jdbc.queryForObject(
                "select actor from audit_log where action = 'test.system' and target = ?", String.class, target))
                .isNull();
    }

    private Map<String, Object> row(String key, String toState) {
        return jdbc.queryForMap(
                "select action, actor, from_state, to_state from audit_log "
                        + "where action = 'settings.changed' and target = ? and to_state = ?", key, toState);
    }
}
