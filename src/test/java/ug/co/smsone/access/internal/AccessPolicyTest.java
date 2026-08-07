package ug.co.smsone.access.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * Devices self-serve and register idempotently; the org security policy enforces its four rules
 * through the REAL filter — an IP outside the allowlist, an over-age token, a call with no trusted
 * device, and a single-factor session under require-MFA each get a policy-named 403, while a
 * compliant call passes. Trust is the org's grant, and a policy denial names its rule so it never
 * reads as RBAC.
 */
@AutoConfigureMockMvc
class AccessPolicyTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void devicesRegisterIdempotentlyAndRevoke() throws Exception {
        // A device belongs to a PERSON, so the token has to resolve to one: subject linked in
        // external_identity, issuer byte-identical to what the resource server validated.
        String subject = "dev-owner-" + UUID.randomUUID();
        EdgeSeed.person(jdbc, subject);
        var me = jwt().jwt(t -> t.claim("iss", EdgeSeed.ISSUER).subject(subject));
        MvcResult first = mockMvc.perform(post("/api/v1/me/devices").with(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Laptop\",\"kind\":\"browser\",\"fingerprint\":\"fp-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.kind").value("BROWSER"))
                .andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.data.id");

        // Same fingerprint re-registers, not duplicates.
        mockMvc.perform(post("/api/v1/me/devices").with(me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Laptop (work)\",\"kind\":\"BROWSER\",\"fingerprint\":\"fp-1\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/me/devices").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attributes.name").value("Laptop (work)"));

        mockMvc.perform(delete("/api/v1/me/devices/{id}", id).with(me)).andExpect(status().isNoContent());
    }

    @Test
    void theThreePolicyRulesEachDenyWithTheirName() throws Exception {
        UUID orgId = seedOrg();
        Member policed = seedMember(orgId, "policed-1", "ORG_READ", "ORG_UPDATE", "SUBSCRIPTION_READ");

        // IP allowlist: MockMvc's remote addr is 127.0.0.1, so an allowlist of 10.0.0.0/8 blocks it.
        setPolicy(orgId, policed, "{\"ipAllowlist\":\"10.0.0.0/8\",\"requireTrustedDevice\":false}");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(policed.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("ip-allowlist")));
        // 127.0.0.1 in the allowlist → passes.
        setPolicy(orgId, policed, "{\"ipAllowlist\":\"127.0.0.0/8\",\"requireTrustedDevice\":false}");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(policed.token()))
                .andExpect(status().isOk());

        // Session max age: a token issued an hour ago is refused when the cap is 60s.
        setPolicy(orgId, policed, "{\"requireTrustedDevice\":false,\"sessionMaxAgeSeconds\":60}");
        var stale = jwt().jwt(t -> t.claim("iss", EdgeSeed.ISSUER).subject(policed.subject())
                .issuedAt(Instant.now().minusSeconds(3600))
                .claim("organization", orgClaim(orgId)));
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(stale))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("session-max-age")));

        // Trusted device: no X-Device-Id → blocked; blessed device present → passes.
        setPolicy(orgId, policed, "{\"requireTrustedDevice\":true}");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(policed.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("trusted-device")));

        // register a device, have the org bless it, then present it. The org blesses it for the
        // PERSON who registered it — trust hangs off person_id now, not off a subject string.
        MvcResult device = mockMvc.perform(post("/api/v1/me/devices").with(policed.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Phone\",\"kind\":\"MOBILE\",\"fingerprint\":\"trusted-fp\"}"))
                .andExpect(status().isCreated()).andReturn();
        String deviceId = JsonPath.read(device.getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(post("/api/v1/orgs/{orgId}/security-policy/trusted-devices", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"" + policed.personId() + "\",\"deviceId\":\"" + deviceId
                                + "\",\"trusted\":true}")
                        .with(policed.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Device-Id", "trusted-fp").with(policed.token()))
                .andExpect(status().isOk());

        // The device's last_seen_at was stamped by the enforcement filter.
        assertThat(jdbc.queryForObject(
                "select count(*) from user_device where fingerprint = 'trusted-fp' and last_seen_at is not null",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void mfaRuleRefusesSingleFactorSessionsButExemptsThePolicyEndpoint() throws Exception {
        UUID orgId = seedOrg();
        Member policed = seedMember(orgId, "policed-mfa", "ORG_READ", "ORG_UPDATE", "SUBSCRIPTION_READ");
        setPolicy(orgId, policed, "{\"requireTrustedDevice\":false,\"requireMfa\":true}");

        // Single-factor session (no amr claim): refused, naming the rule.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(policed.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("mfa")));

        // A session that carried a second factor (amr lists otp) passes — the SAME person, so the
        // only difference between the two calls is the factor the session carried.
        var mfa = jwt().jwt(t -> t.claim("iss", EdgeSeed.ISSUER).subject(policed.subject())
                .claim("amr", java.util.List.of("pwd", "otp"))
                .claim("organization", orgClaim(orgId)));
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(mfa))
                .andExpect(status().isOk());

        // Recovery hatch: the policy's own surface stays reachable to loosen the rule.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/security-policy", orgId).with(policed.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.requireMfa").value(true));
    }

    private void setPolicy(UUID orgId, Member member, String body) throws Exception {
        mockMvc.perform(put("/api/v1/orgs/{orgId}/security-policy", orgId)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(member.token()))
                .andExpect(status().isOk());
    }

    /** A seeded org member: the person the edge resolves to, their subject, and their token. */
    private record Member(UUID personId, String subject, RequestPostProcessor token) {
    }

    /** A tenant the edge can resolve: an organization plus the provider link its claim names. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /**
     * A person holding {@code permissions} in {@code orgId}: the {@code external_identity} link the
     * token resolves through, a role carrying the codes, and the membership joining them.
     */
    private Member seedMember(UUID orgId, String label, String... permissions) {
        String subject = label + "-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'PolRole', false, 0, now())", roleId, orgId,
                "POL_" + roleId.toString().substring(0, 8).toUpperCase());
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
        return new Member(personId, subject, member(orgId, subject));
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
