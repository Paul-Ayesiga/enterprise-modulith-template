package ug.co.smsone.settings;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

@AutoConfigureMockMvc
class SettingsApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminUpsertsAndReadsSettingThroughTheEnvelope() throws Exception {
        mockMvc.perform(put("/api/v1/settings/branding.tagline")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Enterprise messaging\",\"description\":\"Landing tagline\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("setting"))
                .andExpect(jsonPath("$.data.attributes.key").value("branding.tagline"))
                .andExpect(jsonPath("$.data.attributes.value").value("Enterprise messaging"))
                .andExpect(jsonPath("$.meta.requestId").exists());

        mockMvc.perform(get("/api/v1/settings/branding.tagline").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.value").value("Enterprise messaging"))
                .andExpect(jsonPath("$.links.self").value("/api/v1/settings/branding.tagline"));
    }

    @Test
    void nonAdminCannotUpsert() throws Exception {
        mockMvc.perform(put("/api/v1/settings/branding.tagline")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void missingSettingYields404Envelope() throws Exception {
        mockMvc.perform(get("/api/v1/settings/does.not.exist").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void blankValueYields422MultiErrorEnvelope() throws Exception {
        mockMvc.perform(put("/api/v1/settings/branding.tagline")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/value"));
    }
}
