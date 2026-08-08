package ug.co.smsone.access.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The org IP allowlist behind ONE declared proxy hop ({@code app.http.trusted-proxy-hops=1} — the
 * behind-the-gateway topology): the judged address is the rightmost {@code X-Forwarded-For} entry
 * (what our proxy appended), so a caller-forged left entry cannot buy admission, and the raw socket
 * peer (here MockMvc's 127.0.0.1 — "the gateway") no longer decides. The zero-hop default is pinned
 * by {@link ug.co.smsone.shared.web.ForwardedClientIpTest} and {@link AccessPolicyTest}.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.http.trusted-proxy-hops=1")
class AccessPolicyForwardedIpTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allowlistJudgesTheProxyVouchedEntryNotTheSpoofableOne() throws Exception {
        UUID orgId = seedOrg();
        RequestPostProcessor member = seedMember(orgId, "fwd-1");

        mockMvc.perform(put("/api/v1/orgs/{orgId}/security-policy", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ipAllowlist\":\"10.0.0.0/8\",\"requireTrustedDevice\":false}")
                        .with(member))
                .andExpect(status().isOk());

        // Our proxy vouched for 10.1.2.3 (rightmost) → inside the allowlist → admitted,
        // even though the socket peer (127.0.0.1) is not in the list.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Forwarded-For", "203.0.113.9, 10.1.2.3")
                        .with(member))
                .andExpect(status().isOk());

        // The caller forged an allowed address on the LEFT; the vouched entry is outside → refused.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Forwarded-For", "10.1.2.3, 203.0.113.9")
                        .with(member))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail",
                        org.hamcrest.Matchers.containsString("ip-allowlist")));

        // No header at all under declared hops: the socket peer decides (and is outside the list).
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .with(member))
                .andExpect(status().isForbidden());
    }

    /** A tenant the edge can resolve: an organization plus the provider link its claim names. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "fwd-" + UUID.randomUUID());
    }

    /**
     * The org member whose token these requests carry: a person the edge resolves through
     * {@code external_identity}, a role holding the permissions the endpoints ask for, and the
     * membership that ties the two to {@code orgId}. Returns the token that resolves to them.
     *
     * <p>Two spans, because the fixture straddles the tiers (ADR 0010 §2): {@code person} and
     * {@code external_identity} are the platform's — a human is one row however many orgs they sit in —
     * while {@code org_role}, {@code role_permission} and {@code membership} are the tenant's, and on the
     * harness's PLATFORM pin they would be looked for in a schema that does not hold them.
     */
    private RequestPostProcessor seedMember(UUID orgId, String label) {
        String subject = label + "-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);   // PLATFORM half, on the harness pin
        UUID roleId = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> {                // TENANT half, on the org's own axis
            jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                    + "values (?, ?, ?, 'FwdRole', false, 0, now())", roleId, orgId,
                    "FWD_" + roleId.toString().substring(0, 8).toUpperCase());
            for (String permission : new String[] {"ORG_READ", "ORG_UPDATE", "SUBSCRIPTION_READ"}) {
                jdbc.update("insert into role_permission (role_id, permission) values (?, ?)",
                        roleId, permission);
            }
            jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                    + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
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
