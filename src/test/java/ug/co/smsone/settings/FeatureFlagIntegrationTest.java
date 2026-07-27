package ug.co.smsone.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.settings.internal.FeatureFlagService;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

@AutoConfigureMockMvc
class FeatureFlagIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void unknownFlagIsOffNeverAnError() {
        assertThat(featureFlagService.isEnabled("does.not.exist")).isFalse();
    }

    @Test
    void adminTogglesFlagThroughTheApi() throws Exception {
        mockMvc.perform(put("/api/v1/feature-flags/beta.dashboard")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"description\":\"New dashboard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("feature-flag"))
                .andExpect(jsonPath("$.data.attributes.enabled").value(true));

        mockMvc.perform(get("/api/v1/feature-flags/beta.dashboard").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.key").value("beta.dashboard"));

        assertThat(featureFlagService.isEnabled("beta.dashboard")).isTrue();
    }

    @Test
    void evaluationIsCachedAndEvictedOnToggle() {
        featureFlagService.set("cache.flag", true, null);
        assertThat(featureFlagService.isEnabled("cache.flag")).isTrue();

        // flip behind the cache's back: cached evaluation must keep winning
        jdbcTemplate.update("update feature_flag set enabled = false where flag_key = 'cache.flag'");
        assertThat(featureFlagService.isEnabled("cache.flag")).isTrue();

        // a toggle through the service evicts
        featureFlagService.set("cache.flag", false, null);
        assertThat(featureFlagService.isEnabled("cache.flag")).isFalse();
    }

    @Test
    void nonAdminCannotToggle() throws Exception {
        mockMvc.perform(put("/api/v1/feature-flags/beta.locked")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));
    }
}
