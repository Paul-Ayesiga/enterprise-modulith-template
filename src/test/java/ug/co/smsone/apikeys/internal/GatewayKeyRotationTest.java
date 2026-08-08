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
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

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
        UUID orgId = seedOrg();
        RequestPostProcessor gwAdmin = seedMember(orgId, "gw-admin", "ORG_READ", "APIKEY_MANAGE");

        MvcResult minted = mockMvc.perform(post("/api/v1/orgs/{orgId}/api-keys", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gw-bot\",\"permissions\":[\"org:read\"]}")
                        .with(gwAdmin))
                .andExpect(status().isCreated())
                .andReturn();
        String oldKey = JsonPath.read(minted.getResponse().getContentAsString(), "$.data.attributes.secret");
        String keyId = JsonPath.read(minted.getResponse().getContentAsString(), "$.data.id");

        introspect(oldKey).andExpect(jsonPath("$.active").value(true)); // works before rotation

        MvcResult rotated = mockMvc.perform(post("/api/v1/orgs/{orgId}/api-keys/{id}/rotate", orgId, keyId)
                        .with(gwAdmin))
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

    /** A tenant the edge can resolve: an organization plus the provider link its claim names. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /**
     * The human who mints and rotates the key: a person the edge resolves through
     * {@code external_identity}, a role holding {@code permissions}, and the membership joining them
     * to {@code orgId}. Returns the token that resolves to them.
     */
    private RequestPostProcessor seedMember(UUID orgId, String label, String... permissions) {
        String subject = label + "-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        // The seat is TENANT-tier — org_role, role_permission and membership all are (ADR 0010 §2) —
        // so it takes one span on this organization's axis. `api_key` stays platform-tier and is
        // reached by name, which is why the key this test rotates needs no axis of its own.
        TenantContext.runAs(orgId, () -> {
            jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                    + "values (?, ?, ?, 'GwRole', false, 0, now())", roleId, orgId,
                    "GW_" + roleId.toString().substring(0, 8).toUpperCase());
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
