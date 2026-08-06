package ug.co.smsone.localization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/** The catalog's REST contract: envelope, cursor listing, admin-gated writes, audit trail. */
@AutoConfigureMockMvc
class LocalizationApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private MockHttpServletRequestBuilder adminPut(String locale, String key, String value) {
        return put("/api/v1/translations/{locale}/{key}", locale, key)
                .with(jwt().jwt(token -> token.subject("loc-admin"))
                        .authorities(new SimpleGrantedAuthority("ROLE_platform-admin")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + value + "\"}");
    }

    @Test
    void adminUpsertsAndAnyUserReads() throws Exception {
        String key = "api.probe." + UUID.randomUUID();
        mockMvc.perform(adminPut("en", key, "Hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.locale").value("en"))
                .andExpect(jsonPath("$.data.attributes.value").value("Hello"))
                .andExpect(jsonPath("$.meta.requestId").exists());

        mockMvc.perform(get("/api/v1/translations/{locale}/{key}", "EN", key).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.value").value("Hello"));

        mockMvc.perform(get("/api/v1/translations?locale=en&page[size]=5").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.meta.page.size").value(5));
    }

    @Test
    void errorDetailsLocalizeThroughTheCatalogByAcceptLanguage() throws Exception {
        // The error pipeline resolves "error.<code>" per request locale; a catalog gap keeps the
        // author's English detail — additive, so this seeds French and asserts both directions.
        mockMvc.perform(adminPut("fr", "error.resource_not_found", "Ressource introuvable."))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/translations/{locale}/{key}", "en", "missing." + UUID.randomUUID())
                        .with(jwt())
                        .header("Accept-Language", "fr"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].detail").value("Ressource introuvable."));

        mockMvc.perform(get("/api/v1/translations/{locale}/{key}", "en", "missing." + UUID.randomUUID())
                        .with(jwt())
                        .header("Accept-Language", "de"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].detail").value("Translation not found."));
    }

    @Test
    void aNonAdminCannotWrite() throws Exception {
        mockMvc.perform(put("/api/v1/translations/en/api.denied")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));
    }

    @Test
    void anUnparseableLocaleIsA422NotA500() throws Exception {
        mockMvc.perform(adminPut("not a locale!", "api.bad", "x"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].source.parameter").value("locale"));
    }

    @Test
    void deleteRemovesAndAuditsWithBeforeAndAfterState() throws Exception {
        String key = "api.delete." + UUID.randomUUID();
        mockMvc.perform(adminPut("en", key, "doomed")).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/translations/{locale}/{key}", "en", key)
                        .with(jwt().jwt(token -> token.subject("loc-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/translations/{locale}/{key}", "en", key).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));

        List<String> actions = jdbc.queryForList(
                "select action from audit_log where target = ? order by created_at",
                String.class, "en:" + key);
        assertThat(actions).containsExactly(
                "localization.translation_changed", "localization.translation_deleted");
    }
}
