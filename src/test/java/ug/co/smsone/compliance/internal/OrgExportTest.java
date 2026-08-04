package ug.co.smsone.compliance.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The offboarding bundle against real Postgres: only the target org's rows appear, secret-bearing
 * columns never leave the database, and the caps are stated so a truncated table can't read as a
 * complete one.
 */
@AutoConfigureMockMvc
class OrgExportTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exportsTheOrgWithoutSecretsAndWithoutNeighbors() throws Exception {
        UUID target = UUID.randomUUID();
        UUID neighbor = UUID.randomUUID();
        jdbc.update("insert into webhook_subscription (id, org_id, url, secret, event_types, status, created_at, updated_at, version) "
                + "values (?, ?, 'https://t.example/hook', 'ENCRYPTED-SECRET', 'org.member.added', 'ACTIVE', now(), now(), 0)",
                UUID.randomUUID(), target);
        jdbc.update("insert into webhook_subscription (id, org_id, url, secret, event_types, status, created_at, updated_at, version) "
                + "values (?, ?, 'https://n.example/hook', 'NEIGHBOR-SECRET', 'org.member.added', 'ACTIVE', now(), now(), 0)",
                UUID.randomUUID(), neighbor);

        mockMvc.perform(get("/api/v1/admin/compliance/orgs/{orgId}/export", target)
                        .with(jwt().jwt(t -> t.subject("compliance-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("org-export"))
                .andExpect(jsonPath("$.data.attributes.rowCapPerTable").value(1000))
                .andExpect(jsonPath("$.data.attributes.tables.webhook_subscription.length()").value(1))
                .andExpect(jsonPath("$.data.attributes.tables.webhook_subscription[0].url")
                        .value("https://t.example/hook"))
                .andExpect(jsonPath("$.data.attributes.tables.webhook_subscription[0].secret")
                        .doesNotExist());
    }
}
