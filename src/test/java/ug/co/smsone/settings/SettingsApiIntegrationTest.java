package ug.co.smsone.settings;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
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
    void cursorPaginationWalksTheCollection() throws Exception {
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(put("/api/v1/settings/cursor.demo." + i)
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"v" + i + "\"}"))
                    .andExpect(status().isOk());
        }

        var firstPage = mockMvc.perform(get("/api/v1/settings").param("page[size]", "2").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.page.size").value(2))
                .andExpect(jsonPath("$.meta.page.count").value(2))
                .andExpect(jsonPath("$.meta.page.hasMore").value(true))
                .andExpect(jsonPath("$.meta.page.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.links.next", Matchers.containsString("page[after]=")))
                .andReturn();

        String nextCursor = com.jayway.jsonpath.JsonPath.read(
                firstPage.getResponse().getContentAsString(), "$.meta.page.nextCursor");

        mockMvc.perform(get("/api/v1/settings")
                        .param("page[size]", "2")
                        .param("page[after]", nextCursor)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.hasMore").isBoolean())
                .andExpect(jsonPath("$.data[*].attributes.key",
                        Matchers.not(Matchers.hasItem(Matchers.startsWith("ABSENT")))));
    }

    @Test
    void invalidCursorYields422() throws Exception {
        mockMvc.perform(get("/api/v1/settings").param("page[after]", "not-a-cursor!!!").with(jwt()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("page[after]"));

        mockMvc.perform(get("/api/v1/settings").param("page[size]", "9999").with(jwt()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("page[size]"));
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
