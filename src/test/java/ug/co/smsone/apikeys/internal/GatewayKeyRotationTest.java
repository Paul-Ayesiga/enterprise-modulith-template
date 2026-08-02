package ug.co.smsone.apikeys.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Key rotation at the edge, through the introspection seam the gateway uses: a minted key introspects
 * active (the gateway would admit it); after rotating it, the OLD key introspects inactive (revoked —
 * the gateway would 401 it) while the NEW key introspects active. One rotate call, atomically: new key
 * works, old key dead.
 */
@AutoConfigureMockMvc
class GatewayKeyRotationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rotatingAKeyRevokesTheOldAndTheNewOneWorks() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "gw-admin", "ORG_READ", "APIKEY_MANAGE");

        MvcResult minted = mockMvc.perform(post("/api/v1/orgs/{orgId}/api-keys", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gw-bot\",\"permissions\":[\"org:read\"]}")
                        .with(member(orgId, "gw-admin")))
                .andExpect(status().isCreated())
                .andReturn();
        String oldKey = JsonPath.read(minted.getResponse().getContentAsString(), "$.data.attributes.secret");
        String keyId = JsonPath.read(minted.getResponse().getContentAsString(), "$.data.id");

        introspect(oldKey).andExpect(jsonPath("$.active").value(true)); // works before rotation

        MvcResult rotated = mockMvc.perform(post("/api/v1/orgs/{orgId}/api-keys/{id}/rotate", orgId, keyId)
                        .with(member(orgId, "gw-admin")))
                .andExpect(status().isCreated())
                .andReturn();
        String newKey = JsonPath.read(rotated.getResponse().getContentAsString(), "$.data.attributes.secret");

        introspect(oldKey).andExpect(jsonPath("$.active").value(false)); // old key revoked
        introspect(newKey).andExpect(jsonPath("$.active").value(true));  // new key works
    }

    private ResultActions introspect(String apiKey) throws Exception {
        return mockMvc.perform(post("/internal/gateway/api-key/introspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + apiKey + "\"}")
                        .header("X-Gateway-Secret", "dev-gateway-secret"))
                .andExpect(status().isOk());
    }

    private RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }

    private void seedMember(UUID orgId, String subject, String... permissions) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'GwRole', false, 0, now())", roleId, orgId,
                "GW_" + subject.toUpperCase().replace('-', '_'));
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}
