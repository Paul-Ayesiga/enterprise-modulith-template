package ug.co.smsone.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.shared.security.PlatformAdmins;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The REST-level compliance guarantees: consent is append-only; an erasure soft-deletes a data
 * subject's rows; a legal hold makes an erasure REFUSED. The purge-survival half (a held row
 * outlives the purge) lives in {@code scheduler.internal.LegalHoldPurgeTest}, where the
 * package-private purge job is reachable.
 *
 * <p>Every caller here is seeded as a {@code person} with an {@code external_identity} link, because
 * that link is now what a token resolves through — a bare {@code jwt()} subject reaches no person, and
 * these endpoints refuse a caller who is not one.
 */
@AutoConfigureMockMvc
class ComplianceTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // The role data lives in Keycloak; stub the port so the guard's refusal is tested without a live IdP.
    @MockitoBean
    private PlatformAdmins platformAdmins;

    @Test
    void consentIsAppendOnlyAndErasureSoftDeletes() throws Exception {
        UUID personId = seedPerson();
        var me = tokenFor(personId);

        mockMvc.perform(post("/api/v1/me/consents").with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"marketing\",\"granted\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/me/consents").with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"marketing\",\"granted\":false}"))
                .andExpect(status().isOk());
        // Both rows survive — a withdrawal is a new record, not an overwrite.
        mockMvc.perform(get("/api/v1/me/consents").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(post("/api/v1/me/erasure-request").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("EXECUTED"));
        assertThat(livePeople(personId)).as("erasure soft-deleted the person").isZero();
        // The link goes too, and that is the point rather than tidiness: uq_external_identity_subject_live
        // is partial on deleted_at, so a live link left behind is an erased account that still signs in.
        assertThat(jdbc.queryForObject(
                "select count(*) from external_identity where person_id = ? and deleted_at is null",
                Integer.class, personId)).as("an erased person cannot still authenticate").isZero();
    }

    @Test
    void aLegalHoldMakesErasureRefused() throws Exception {
        UUID personId = seedPerson();
        mockMvc.perform(post("/api/v1/admin/compliance/legal-holds/subject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"" + personId + "\",\"reason\":\"litigation #99\"}")
                        .with(admin()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/compliance/erasure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"" + personId + "\"}").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("REFUSED"));
        // Refused erasure means the person was NOT soft-deleted.
        assertThat(livePeople(personId)).isEqualTo(1);
    }

    @Test
    void erasingTheLastPlatformSuperAdminIsRefused() throws Exception {
        UUID personId = seedPerson();
        given(platformAdmins.isSoleSuperAdmin(personId)).willReturn(true);

        mockMvc.perform(post("/api/v1/me/erasure-request").with(tokenFor(personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("REFUSED"));
        assertThat(livePeople(personId)).as("the last super-admin must not be erased").isEqualTo(1);
    }

    /** A platform operator: a realm role and no person of their own — nothing here reads one. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("compliance-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    private UUID seedPerson() {
        UUID personId = UUID.randomUUID();
        return EdgeSeed.personWithEmail(jdbc, EdgeSeed.subjectFor(personId), personId + "@smsone.co.ug");
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor tokenFor(UUID personId) {
        String subject = jdbc.queryForObject(
                "select external_subject from external_identity where person_id = ?", String.class, personId);
        return jwt().jwt(t -> t.subject(subject));
    }

    private int livePeople(UUID personId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from person where id = ? and deleted_at is null", Integer.class, personId);
        return count == null ? 0 : count;
    }
}
