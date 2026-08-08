package ug.co.smsone.scheduler.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.shared.retention.RetentionScope;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The per-org retention-override admin surface, and that the shared port reflects it: platform-admin
 * sets/clears an org's retention for a scope, platform-support reads it, an unknown scope is 422.
 */
@AutoConfigureMockMvc
class RetentionOverrideApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RetentionOverridesImpl overrides;

    @Test
    void platformSetsListsAndClearsAnOrgRetentionOverrideAndThePortReflectsIt() throws Exception {
        UUID orgId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/admin/orgs/{orgId}/retention/{scope}", orgId, "exchange-job")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\":365}").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.scope").value("EXCHANGE_JOB"))
                .andExpect(jsonPath("$.data.attributes.retentionDays").value(365));

        // The shared port (what the retention jobs consult) now carries this org's override — read on
        // the org's OWN home, because that is where the retention jobs read it from since ADR 0010
        // Phase 5. daysByScope declares no axis of its own precisely so a fan-out can decide which home
        // it is answering for; asserting it from the harness's PLATFORM pin would ask the wrong schema.
        assertThat(TenantContext.callAs(orgId, () -> overrides.daysByScope(RetentionScope.EXCHANGE_JOB)))
                .containsEntry(orgId, 365);

        mockMvc.perform(get("/api/v1/admin/orgs/{orgId}/retention", orgId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.retentionDays").value(365));

        // An unknown scope is a 422, not a silent no-op.
        mockMvc.perform(put("/api/v1/admin/orgs/{orgId}/retention/{scope}", orgId, "bogus-scope")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\":10}").with(admin()))
                .andExpect(status().isUnprocessableContent());

        mockMvc.perform(delete("/api/v1/admin/orgs/{orgId}/retention/{scope}", orgId, "exchange-job")
                        .with(admin()))
                .andExpect(status().isNoContent());
        assertThat(TenantContext.callAs(orgId, () -> overrides.daysByScope(RetentionScope.EXCHANGE_JOB)))
                .doesNotContainKey(orgId);
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("platform-admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"),
                        new SimpleGrantedAuthority("ROLE_platform-support"));
    }
}
