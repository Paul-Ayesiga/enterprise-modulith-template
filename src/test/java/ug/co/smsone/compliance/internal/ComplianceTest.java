package ug.co.smsone.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The REST-level compliance guarantees: consent is append-only; an erasure soft-deletes a
 * subject's data; a legal hold makes an erasure REFUSED. The purge-survival half (a held row
 * outlives the purge) lives in {@code scheduler.internal.LegalHoldPurgeTest}, where the
 * package-private purge job is reachable.
 */
@AutoConfigureMockMvc
class ComplianceTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void consentIsAppendOnlyAndErasureSoftDeletes() throws Exception {
        String subject = "gdpr-" + UUID.randomUUID();
        var me = jwt().jwt(t -> t.subject(subject));
        seedUser(subject);

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
        assertThat(jdbc.queryForObject(
                "select count(*) from app_user where subject = ? and deleted_at is not null",
                Integer.class, subject)).as("erasure soft-deleted the user").isEqualTo(1);
    }

    @Test
    void aLegalHoldMakesErasureRefused() throws Exception {
        String subject = "held-" + UUID.randomUUID();
        seedUser(subject);
        mockMvc.perform(post("/api/v1/admin/compliance/legal-holds/subject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"" + subject + "\",\"reason\":\"litigation #99\"}")
                        .with(admin()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/compliance/erasure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"" + subject + "\"}").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("REFUSED"));
        // Refused erasure means the user was NOT soft-deleted.
        assertThat(jdbc.queryForObject(
                "select count(*) from app_user where subject = ? and deleted_at is null",
                Integer.class, subject)).isEqualTo(1);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("compliance-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    private void seedUser(String subject) {
        jdbc.update("insert into app_user (id, subject, email, status, provisioned_at, version, created_at) "
                + "values (?, ?, ?, 'ACTIVE', now(), 0, now())",
                UUID.randomUUID(), subject, subject + "@smsone.co.ug");
    }
}
