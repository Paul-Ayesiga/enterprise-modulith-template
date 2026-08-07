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
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * One invariant, three call sites: <b>anything durable keys on {@code person.id}</b>. Nothing keys on
 * {@code preferred_username}, and nothing keys on the token subject either any more — the subject is a
 * provider's name for someone, and this platform has its own.
 *
 * <p>The username half of the rule is the older half and still bites: Keycloak usernames are mutable
 * and, once freed, reassignable, so a name-keyed record is both losable (rename yourself and your
 * history, quota and keys detach) and stealable (take a freed name and inherit the previous holder's).
 * The subject half is what this rewrite adds: two subjects can now resolve to ONE person — a second
 * identity provider, or a Keycloak account re-created after a realm rebuild — and durable rows must
 * follow the human, not the credential they happened to sign in with.
 *
 * <p>These tests build the {@code Authentication} through the REAL
 * {@link KeycloakJwtAuthenticationConverter}, which is the only way the username bug is observable: the
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

    /**
     * A token shaped the way Keycloak actually issues one: {@code preferred_username != sub}, and an
     * {@code iss} that matches what {@code external_identity} was seeded with — without the latter the
     * token resolves to no person at all and every assertion below would be about a null.
     */
    private static RequestPostProcessor caller(String subject, String username) {
        return authentication(CONVERTER.convert(token(subject, username)));
    }

    private static Jwt token(String subject, String username) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(PlatformRole.ADMIN)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private static String settingBody(String value) {
        return "{\"value\":\"" + value + "\"}";
    }

    /** A person and the Keycloak link a token reaches them through. Returns their person id. */
    private UUID person(String subject) {
        return EdgeSeed.person(jdbc, subject);
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

    /**
     * The resolution itself: a token names a subject, and what comes out the other side is a person.
     * {@code CurrentUser} carries no subject and no username — the two mutable, provider-owned strings
     * that used to reach every consumer.
     */
    @Test
    void aTokenResolvesToAPersonAndCarriesNoProviderStrings() {
        String subject = "sub-" + UUID.randomUUID();
        UUID personId = person(subject);
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(CONVERTER.convert(token(subject, "alice")));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            assertThat(currentUserProvider.currentPersonId()).contains(personId);
            assertThat(currentUserProvider.currentPrincipalKey()).contains("person:" + personId);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    /** A validated token that no {@code external_identity} row names is nobody — no JIT, ever. */
    @Test
    void anUnlinkedTokenResolvesToNoPersonAndNoPrincipalKey() {
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(CONVERTER.convert(token("sub-unlinked-" + UUID.randomUUID(), "ghost")));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            assertThat(currentUserProvider.currentPersonId()).isEmpty();
            assertThat(currentUserProvider.currentPrincipalKey()).isEmpty();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void auditColumnsRecordThePersonNotTheSubjectOrTheUsername() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        UUID personId = person(subject);
        String key = "attribution.probe." + UUID.randomUUID();

        mockMvc.perform(put("/api/v1/settings/{key}", key)
                        .with(caller(subject, "display-name-that-may-change"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("v1")))
                .andExpect(status().isOk());

        UUID createdBy = jdbc.queryForObject(
                "select created_by from setting where setting_key = ?", UUID.class, key);
        assertThat(createdBy)
                .as("created_by must be the person, not a provider's name for them")
                .isEqualTo(personId);
    }

    @Test
    void updatedByAlsoRecordsThePerson() throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        UUID personId = person(subject);
        String key = "attribution.update." + UUID.randomUUID();

        String firstSubject = "sub-" + UUID.randomUUID();
        person(firstSubject);
        mockMvc.perform(put("/api/v1/settings/{key}", key)
                        .with(caller(firstSubject, "original-author"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("v1")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/settings/{key}", key)
                        .with(caller(subject, "second-editor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("v2")))
                .andExpect(status().isOk());

        UUID updatedBy = jdbc.queryForObject(
                "select updated_by from setting where setting_key = ?", UUID.class, key);
        assertThat(updatedBy).isEqualTo(personId);
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
        String first = "sub-first-" + UUID.randomUUID();
        String second = "sub-second-" + UUID.randomUUID();
        person(first);
        person(second);

        mockMvc.perform(put("/api/v1/settings/{key}", settingKey)
                        .with(caller(first, sharedUsername))
                        .header(IdempotencyFilter.KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("owned-by-first")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

        // Same username, same key, same payload — a different human. Must execute, never replay.
        mockMvc.perform(put("/api/v1/settings/{key}", settingKey)
                        .with(caller(second, sharedUsername))
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
        person(subject);
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

    /**
     * The new half of the rule, and the one the rename to {@code person.id} exists for: TWO credentials
     * that resolve to one person share everything durable. A second identity provider, or a Keycloak
     * account re-created after a realm rebuild, is a second {@code external_identity} row — not a second
     * human, and not a second idempotency namespace.
     */
    @Test
    void twoCredentialsForOnePersonShareTheirIdempotencyNamespace() throws Exception {
        String firstSubject = "sub-a-" + UUID.randomUUID();
        String secondSubject = "sub-b-" + UUID.randomUUID();
        UUID personId = person(firstSubject);
        EdgeSeed.link(jdbc, personId, secondSubject); // same human, second credential
        String idempotencyKey = "two-creds-" + UUID.randomUUID();
        String settingKey = "attribution.twocreds." + UUID.randomUUID();

        mockMvc.perform(put("/api/v1/settings/{key}", settingKey)
                        .with(caller(firstSubject, "ada"))
                        .header(IdempotencyFilter.KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("once")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

        mockMvc.perform(put("/api/v1/settings/{key}", settingKey)
                        .with(caller(secondSubject, "ada"))
                        .header(IdempotencyFilter.KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingBody("once")))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));
    }
}
