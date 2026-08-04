package ug.co.smsone.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Flag targeting precedence, end to end on real Postgres: org override → percentage bucket (only
 * while the flag is enabled) → global value; unknown flags stay false. 0% and 100% are the
 * deterministic edges every org agrees on; stickiness is asserted by re-asking.
 */
@AutoConfigureMockMvc
class FeatureFlagTargetingTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureFlagService flags;

    @Test
    void precedenceIsOverrideThenPercentageThenGlobal() {
        UUID org = UUID.randomUUID();

        // Enabled at 0%: nobody is in the bucket.
        flags.set("beta.exports", true, null, 0);
        assertThat(flags.isEnabledFor("beta.exports", org)).isFalse();

        // A hard org override beats the percentage.
        flags.setOrgOverride("beta.exports", org, true);
        assertThat(flags.isEnabledFor("beta.exports", org)).isTrue();

        // Clearing it falls back to the 0% bucket.
        flags.clearOrgOverride("beta.exports", org);
        assertThat(flags.isEnabledFor("beta.exports", org)).isFalse();

        // 100% admits everyone; no percentage means plain enabled.
        flags.set("beta.exports", true, null, 100);
        assertThat(flags.isEnabledFor("beta.exports", org)).isTrue();
        flags.set("beta.exports", true, null, null);
        assertThat(flags.isEnabledFor("beta.exports", org)).isTrue();

        // A disabled flag is off for everyone — percentage only widens an enabled flag.
        flags.set("beta.exports", false, null, 100);
        assertThat(flags.isEnabledFor("beta.exports", org)).isFalse();
        // ...but an override still pins it on.
        flags.setOrgOverride("beta.exports", org, true);
        assertThat(flags.isEnabledFor("beta.exports", org)).isTrue();
    }

    @Test
    void bucketingIsStickyPerOrg() {
        UUID org = UUID.randomUUID();
        flags.set("beta.sticky", true, null, 50);
        boolean first = flags.isEnabledFor("beta.sticky", org);
        for (int i = 0; i < 5; i++) {
            assertThat(flags.isEnabledFor("beta.sticky", org)).isEqualTo(first);
        }
    }

    @Test
    void unknownFlagAndBadInputs() {
        assertThat(flags.isEnabledFor("does.not.exist", UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> flags.setOrgOverride("does.not.exist", UUID.randomUUID(), true))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> flags.set("bad.pct", true, null, 150))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void adminManagesOverridesThroughTheApi() throws Exception {
        UUID org = UUID.randomUUID();
        flags.set("beta.api", false, null, null);

        mockMvc.perform(put("/api/v1/feature-flags/beta.api/orgs/" + org)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("feature-flag-evaluation"))
                .andExpect(jsonPath("$.data.attributes.enabled").value(true));

        mockMvc.perform(delete("/api/v1/feature-flags/beta.api/orgs/" + org)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-admin"))))
                .andExpect(status().isNoContent());

        assertThat(flags.isEnabledFor("beta.api", org)).isFalse();

        // Not platform-admin → the write is refused.
        mockMvc.perform(put("/api/v1/feature-flags/beta.api/orgs/" + org)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }
}
