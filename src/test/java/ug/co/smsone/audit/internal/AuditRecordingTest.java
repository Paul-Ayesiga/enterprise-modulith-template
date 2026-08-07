package ug.co.smsone.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ug.co.smsone.settings.internal.Setting;
import ug.co.smsone.settings.internal.SettingService;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * Audit capture through a real service: a change records WHO — as a {@code person.id}, the platform's
 * only durable answer — plus what and the from→to state, atomically with the change. Also proves
 * {@code actor_person_id} is NULL for a system-triggered change, which is V13's spelling for "a
 * non-person actor" rather than a missing value.
 *
 * <p>The caller is seeded as a {@code person} with an {@code external_identity} link and the token
 * carries the matching {@code iss}: that pair is what the edge resolves, and a token without it is
 * indistinguishable from a system job as far as attribution goes.
 */
class AuditRecordingTest extends AbstractIntegrationTest {

    @Autowired
    private SettingService settings;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private AuditEntryRepository auditEntries;

    @Autowired
    private JpaRepository<Setting, UUID> settingRows; // SettingRepository, via its public supertype

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Seeds a person, links it, and installs a token that resolves to it. Returns their person id. */
    private UUID authenticateAsAPerson() {
        String subject = "audit-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject)
                // The iss must be the configured issuer: external_identity is keyed on it, and a token
                // from an unknown issuer deliberately resolves to no person at all.
                .claim("iss", EdgeSeed.ISSUER)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return personId;
    }

    @Test
    void settingChangeRecordsWhoWhatAndFromToState() {
        String key = "billing.mode-" + UUID.randomUUID();
        UUID actor = authenticateAsAPerson();

        settings.put(key, "monthly", "Billing cadence");
        Map<String, Object> first = row(key, "monthly");
        assertThat(first.get("actor_person_id")).isEqualTo(actor);
        assertThat(first.get("from_state")).isNull();
        assertThat(first.get("to_state")).isEqualTo("monthly");
        assertThat(first.get("action")).isEqualTo("settings.changed");

        settings.put(key, "annual", "Billing cadence");
        Map<String, Object> second = row(key, "annual");
        assertThat(second.get("from_state")).isEqualTo("monthly"); // the prior value
        assertThat(second.get("to_state")).isEqualTo("annual");
        assertThat(second.get("actor_person_id")).isEqualTo(actor);
    }

    @Test
    void systemTriggeredChangeHasNoActor() {
        SecurityContextHolder.clearContext(); // no authenticated principal
        String target = "sys-" + UUID.randomUUID();

        auditLog.record("test.system", null, target, "off", "on");

        assertThat(jdbc.queryForObject(
                "select actor_person_id from audit_log where action = 'test.system' and target = ?",
                UUID.class, target))
                .isNull();
    }

    /**
     * The trail outlives what it describes. {@code audit_log} is deliberately the one table V17 left
     * alone — {@link AuditEntry} extends {@code BaseEntity}, not {@code SoftDeletableEntity} — so
     * soft-deleting the entity an entry describes must not hide, orphan or cascade into its history.
     * Deleting a setting is exactly the case where the audit row becomes the ONLY surviving record of
     * who changed what.
     */
    @Test
    void theTrailSurvivesTheSoftDeleteOfWhatItDescribes() {
        String key = "retention.window-" + UUID.randomUUID();
        UUID actor = authenticateAsAPerson();
        settings.put(key, "30d", null);
        settings.put(key, "90d", null);

        Setting setting = settings.require(key);
        settingRows.delete(setting);

        assertThat(settingRows.findById(setting.getId())).isEmpty(); // the subject is gone from JPA...
        assertThat(auditEntries.findAll(recordsFor(key)))            // ...its history is not
                .extracting(AuditEntry::getToState)
                .containsExactlyInAnyOrder("30d", "90d");
        assertThat(auditEntries.findAll(recordsFor(key)))
                .extracting(AuditEntry::getActorPersonId)
                .containsOnly(actor);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where target = ?", Integer.class, key)).isEqualTo(2);
    }

    private static Specification<AuditEntry> recordsFor(String target) {
        return (root, query, cb) -> cb.equal(root.get("target"), target);
    }

    /**
     * {@code actor_person_id}, not {@code actor}: V13 replaced the subject string with the person id, so
     * the column the assertions read back is a {@code uuid} and the map key is its name.
     */
    private Map<String, Object> row(String key, String toState) {
        return jdbc.queryForMap(
                "select action, actor_person_id, from_state, to_state from audit_log "
                        + "where action = 'settings.changed' and target = ? and to_state = ?", key, toState);
    }
}
