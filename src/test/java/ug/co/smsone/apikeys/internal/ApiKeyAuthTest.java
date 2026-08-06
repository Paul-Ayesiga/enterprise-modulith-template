package ug.co.smsone.apikeys.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The machine-credential contract end to end: an org key mints only a SUBSET of its creator's
 * permissions, authenticates via X-Api-Key, reaches exactly what its subset allows and no more,
 * a revoked key 401s, and a platform key reads support surfaces but never an admin one.
 */
@AutoConfigureMockMvc
class ApiKeyAuthTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anOrgKeyCarriesASubsetAuthenticatesAndIsRevocable() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "key-admin", "ORG_READ", "MEMBER_READ", "APIKEY_MANAGE");

        // Mint a key with a subset of what the caller holds.
        MvcResult minted = mockMvc.perform(post("/api/v1/orgs/{orgId}/api-keys", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci-bot\",\"permissions\":[\"org:read\"]}")
                        .with(member(orgId, "key-admin")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.secret").exists())
                .andExpect(jsonPath("$.data.attributes.prefix").exists())
                .andReturn();
        String secret = JsonPath.read(minted.getResponse().getContentAsString(), "$.data.attributes.secret");
        String keyId = JsonPath.read(minted.getResponse().getContentAsString(), "$.data.id");

        // The key reaches org:read...
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Api-Key", secret))
                .andExpect(status().isOk());
        // ...but NOT member:read (not in its subset) — even though the human minter holds it.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId)
                        .header("X-Api-Key", secret))
                .andExpect(status().isForbidden());
        // ...and NOTHING in another org.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", UUID.randomUUID())
                        .header("X-Api-Key", secret))
                .andExpect(status().isForbidden());

        // Revoke → the very next call 401s (no principal).
        mockMvc.perform(delete("/api/v1/orgs/{orgId}/api-keys/{id}", orgId, keyId)
                        .with(member(orgId, "key-admin")))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Api-Key", secret))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aKeyCannotOutrankItsCreator() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "limited-admin", "ORG_READ", "APIKEY_MANAGE"); // no member:read

        mockMvc.perform(post("/api/v1/orgs/{orgId}/api-keys", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"greedy\",\"permissions\":[\"org:read\",\"member:read\"]}")
                        .with(member(orgId, "limited-admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail",
                        org.hamcrest.Matchers.containsString("member:read")));

        // A bad secret authenticates nothing → 401, and never confirms whether the prefix exists.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Api-Key", "sk_deadbeef.not-a-real-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aPlatformKeyReadsSupportSurfacesButNotAdminOnes() throws Exception {
        MvcResult minted = mockMvc.perform(post("/api/v1/admin/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ops-reader\"}")
                        .with(jwt().jwt(t -> t.subject("plat-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"))))
                .andExpect(status().isCreated())
                .andReturn();
        String secret = JsonPath.read(minted.getResponse().getContentAsString(), "$.data.attributes.secret");

        // Support surface: reachable.
        mockMvc.perform(get("/api/v1/admin/orgs").header("X-Api-Key", secret))
                .andExpect(status().isOk());
        // Admin-only surface (minting keys): the support tier does not satisfy platform-admin.
        mockMvc.perform(post("/api/v1/admin/api-keys")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")
                        .header("X-Api-Key", secret))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
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
                + "values (?, ?, ?, 'KeyRole', false, 0, now())", roleId, orgId,
                "KEY_" + subject.toUpperCase().replace('-', '_'));
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}
