package ug.co.smsone.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.shared.idempotency.IdempotencyFilter;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * One invariant, three call sites: anything durable keys on the token SUBJECT, never on
 * {@code preferred_username}. Keycloak usernames are mutable and, once freed, reassignable — so a
 * name-keyed record is both losable (rename yourself and your history/quota/keys detach) and
 * stealable (take a freed name and inherit the previous holder's).
 *
 * <p>These tests build the {@code Authentication} through the REAL
 * {@link KeycloakJwtAuthenticationConverter}, which is the only way the bug is observable: the
 * converter sets the principal name from {@code preferred_username}, whereas the {@code jwt()} mock
 * post-processor used elsewhere in the suite defaults the name to the subject — so those tests pass
 * either way and never constrained this.
 */
@AutoConfigureMockMvc
class SubjectAttributionTest extends AbstractIntegrationTest {

    private static final KeycloakJwtAuthenticationConverter CONVERTER = new KeycloakJwtAuthenticationConverter();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    /** A token shaped the way Keycloak actually issues one: {@code preferred_username != sub}. */
    private static RequestPostProcessor caller(String subject, String username) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(PlatformRole.ADMIN)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return authentication(CONVERTER.convert(jwt));
    }

    private static String settingBody(String value) {
        return "{\"value\":\"" + value + "\"}";
    }

    @Test
    void theConverterDoesNameThePrincipalAfterTheUsername() {
        // Guards the premise of every other test here: if this stopped being true, the tests below
        // would keep passing while proving nothing.
        assertThat(CONVERTER.convert(Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("sub-abc").claim("preferred_username", "renamed-later")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build()).getName())
                .isEqualTo("renamed-later");
    }

    @Test
    void currentSubjectIsTheSubjectWhileUsernameStaysTheDisplayName() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("sub-1234").claim("preferred_username", "alice")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(CONVERTER.convert(jwt));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            assertThat(currentUserProvider.currentSubject()).contains("sub-1234");
            assertThat(currentUserProvider.currentUser().orElseThrow().username()).isEqualTo("alice");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void auditColumnsRecordTheSubjectNotTheUsername() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String key = "attribution.probe." + UUID.randomUUID();

        mockMvc.perform(put("/api/v1/settings/{key}", key)
                        .with(caller(subject, "display-name-that-may-change"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("v1")))
                .andExpect(status().isOk());

        String createdBy = jdbc.queryForObject(
                "select created_by from setting where setting_key = ?", String.class, key);
        assertThat(createdBy)
                .as("created_by must be the immutable subject, not the display name")
                .isEqualTo(subject);
    }

    @Test
    void updatedByAlsoRecordsTheSubject() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String key = "attribution.update." + UUID.randomUUID();

        mockMvc.perform(put("/api/v1/settings/{key}", key)
                        .with(caller("sub-" + UUID.randomUUID(), "original-author"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("v1")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/settings/{key}", key)
                        .with(caller(subject, "second-editor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("v2")))
                .andExpect(status().isOk());

        String updatedBy = jdbc.queryForObject(
                "select updated_by from setting where setting_key = ?", String.class, key);
        assertThat(updatedBy).isEqualTo(subject);
    }

    /**
     * The disclosure case: usernames can be freed and reassigned in Keycloak. If idempotency keys were
     * scoped by name, the new holder of a recycled username would replay the previous holder's stored
     * response bodies.
     */
    @Test
    void twoAccountsSharingAUsernameDoNotShareIdempotencyKeys() throws Exception {
        String sharedUsername = "recycled-name";
        String idempotencyKey = "shared-name-" + UUID.randomUUID();
        String settingKey = "attribution.idem." + UUID.randomUUID();

        mockMvc.perform(put("/api/v1/settings/{key}", settingKey)
                        .with(caller("sub-first-" + UUID.randomUUID(), sharedUsername))
                        .header(IdempotencyFilter.KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("owned-by-first")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

        // Same username, same key, same payload — a different human. Must execute, never replay.
        mockMvc.perform(put("/api/v1/settings/{key}", settingKey)
                        .with(caller("sub-second-" + UUID.randomUUID(), sharedUsername))
                        .header(IdempotencyFilter.KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("owned-by-first")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));
    }

    /** The mirror case: renaming yourself must not detach you from your own in-flight keys. */
    @Test
    void oneAccountKeepsItsIdempotencyKeysAcrossARename() throws Exception {
        String subject = "sub-stable-" + UUID.randomUUID();
        String idempotencyKey = "rename-" + UUID.randomUUID();
        String settingKey = "attribution.rename." + UUID.randomUUID();

        mockMvc.perform(put("/api/v1/settings/{key}", settingKey)
                        .with(caller(subject, "name-before"))
                        .header(IdempotencyFilter.KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("once")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

        mockMvc.perform(put("/api/v1/settings/{key}", settingKey)
                        .with(caller(subject, "name-after"))
                        .header(IdempotencyFilter.KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("once")))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));
    }
}
