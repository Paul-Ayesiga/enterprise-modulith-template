package ug.co.smsone.apikeys.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The gateway key-introspection seam: the gateway presents the shared secret and a key, and the
 * modulith answers with the key's principal (subject/tenant/scopes) — backed by the same
 * {@code ApiKeyAuthenticator} its own filter uses. A wrong secret is 401; an unverifiable key is
 * {@code active:false} (not 401 — the CALL succeeded, the key just did not verify).
 */
@AutoConfigureMockMvc
class GatewayIntrospectionTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void introspectsAValidKeyWithTheGatewaySecret() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "gw-admin", "ORG_READ", "APIKEY_MANAGE");
        MvcResult minted = mockMvc.perform(post("/api/v1/orgs/{orgId}/api-keys", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gw-bot\",\"permissions\":[\"org:read\"]}")
                        .with(member(orgId, "gw-admin")))
                .andExpect(status().isCreated())
                .andReturn();
        String presentedKey = JsonPath.read(minted.getResponse().getContentAsString(), "$.data.attributes.secret");

        mockMvc.perform(post("/internal/gateway/api-key/introspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + presentedKey + "\"}")
                        .header("X-Gateway-Secret", "dev-gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.subject").value(Matchers.startsWith("key:")))
                .andExpect(jsonPath("$.tenant").value(orgId.toString()))
                .andExpect(jsonPath("$.scopes[0]").value("org:read"));
    }

    @Test
    void wrongGatewaySecretIs401() throws Exception {
        mockMvc.perform(post("/internal/gateway/api-key/introspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk_x.y\"}")
                        .header("X-Gateway-Secret", "wrong-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnverifiableKeyIntrospectsInactive() throws Exception {
        mockMvc.perform(post("/internal/gateway/api-key/introspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk_deadbeef.not-a-real-secret\"}")
                        .header("X-Gateway-Secret", "dev-gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
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
