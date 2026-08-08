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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

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
        UUID orgId = seedOrg();
        RequestPostProcessor keyAdmin =
                seedMember(orgId, "key-admin", "ORG_READ", "MEMBER_READ", "SUBSCRIPTION_READ", "APIKEY_MANAGE");

        // Mint a key with a subset of what the caller holds.
        MvcResult minted = mockMvc.perform(post("/api/v1/orgs/{orgId}/api-keys", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci-bot\",\"permissions\":[\"subscription:read\"]}")
                        .with(keyAdmin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.secret").exists())
                .andExpect(jsonPath("$.data.attributes.prefix").exists())
                .andReturn();
        String secret = JsonPath.read(minted.getResponse().getContentAsString(), "$.data.attributes.secret");
        String keyId = JsonPath.read(minted.getResponse().getContentAsString(), "$.data.id");

        // The key reaches subscription:read...
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
                        .with(keyAdmin))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Api-Key", secret))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aKeyCannotOutrankItsCreator() throws Exception {
        UUID orgId = seedOrg();
        // no member:read
        RequestPostProcessor limitedAdmin = seedMember(orgId, "limited-admin", "ORG_READ", "APIKEY_MANAGE");

        mockMvc.perform(post("/api/v1/orgs/{orgId}/api-keys", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"greedy\",\"permissions\":[\"org:read\",\"member:read\"]}")
                        .with(limitedAdmin))
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

    /** A tenant the edge can resolve: an organization plus the provider link its claim names. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /**
     * The human minter: a person the edge resolves through {@code external_identity}, a role holding
     * {@code permissions}, and the membership joining them to {@code orgId} — the set a minted key is
     * capped against. Returns the token that resolves to them.
     */
    private RequestPostProcessor seedMember(UUID orgId, String label, String... permissions) {
        String subject = label + "-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        // The seat is TENANT-tier — org_role, role_permission and membership all are (ADR 0010 §2) —
        // so it takes one span on this organization's axis. `api_key` itself is platform-tier and is
        // deliberately NOT seeded here: the key is minted through the REST surface, which resolves it
        // by name from whichever axis the caller is on.
        TenantContext.runAs(orgId, () -> {
            jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                    + "values (?, ?, ?, 'KeyRole', false, 0, now())", roleId, orgId,
                    "KEY_" + roleId.toString().substring(0, 8).toUpperCase());
            for (String permission : permissions) {
                jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
            }
            jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                    + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
            // Qualified, so it lands in the platform schema from inside the tenant's axis — the pair
            // OrgProjectionWriter writes in one transaction for every real seat (ADR 0010 §2.1).
            jdbc.update("insert into platform.org_membership_index (person_id, org_id, status) "
                    + "values (?, ?, 'ACTIVE') on conflict (person_id, org_id) do nothing",
                    personId, orgId);
        });
        return member(orgId, subject);
    }

    /**
     * A token for a seeded person: {@code iss} must be {@link EdgeSeed#ISSUER} byte-for-byte or the
     * (issuer, subject) pair resolves to no person, and the alias-keyed {@code organization} claim
     * carries the PROVIDER's org id — both rebuilt from what {@link EdgeSeed} wrote.
     */
    private RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.claim("iss", EdgeSeed.ISSUER).subject(subject)
                .claim("organization", orgClaim(orgId)));
    }

    /** The alias-keyed {@code organization} claim Keycloak mints, rebuilt from the seeded link. */
    private Map<String, Object> orgClaim(UUID orgId) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return Map.of(String.valueOf(link.get("external_alias")),
                Map.of("id", String.valueOf(link.get("external_org_id"))));
    }
}
