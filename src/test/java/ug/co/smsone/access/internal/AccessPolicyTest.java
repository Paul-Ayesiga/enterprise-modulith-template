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
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Devices self-serve and register idempotently; the org security policy enforces its three rules
 * through the REAL filter — an IP outside the allowlist, an over-age token, and a call with no
 * trusted device each get a policy-named 403, while a compliant call passes. Trust is the org's
 * grant, and a policy denial names its rule so it never reads as RBAC.
 */
@AutoConfigureMockMvc
class AccessPolicyTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void devicesRegisterIdempotentlyAndRevoke() throws Exception {
        var me = jwt().jwt(t -> t.subject("dev-owner-" + UUID.randomUUID()));
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
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "policed-1", "ORG_READ", "ORG_UPDATE");

        // IP allowlist: MockMvc's remote addr is 127.0.0.1, so an allowlist of 10.0.0.0/8 blocks it.
        setPolicy(orgId, "policed-1","{\"ipAllowlist\":\"10.0.0.0/8\",\"requireTrustedDevice\":false}");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(member(orgId, "policed-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("ip-allowlist")));
        // 127.0.0.1 in the allowlist → passes.
        setPolicy(orgId, "policed-1","{\"ipAllowlist\":\"127.0.0.0/8\",\"requireTrustedDevice\":false}");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(member(orgId, "policed-1")))
                .andExpect(status().isOk());

        // Session max age: a token issued an hour ago is refused when the cap is 60s.
        setPolicy(orgId, "policed-1","{\"requireTrustedDevice\":false,\"sessionMaxAgeSeconds\":60}");
        var stale = jwt().jwt(t -> t.subject("policed-1").issuedAt(Instant.now().minusSeconds(3600))
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(stale))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("session-max-age")));

        // Trusted device: no X-Device-Id → blocked; blessed device present → passes.
        setPolicy(orgId, "policed-1","{\"requireTrustedDevice\":true}");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(member(orgId, "policed-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("trusted-device")));

        // register a device, have the org bless it, then present it.
        MvcResult device = mockMvc.perform(post("/api/v1/me/devices").with(member(orgId, "policed-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Phone\",\"kind\":\"MOBILE\",\"fingerprint\":\"trusted-fp\"}"))
                .andExpect(status().isCreated()).andReturn();
        String deviceId = JsonPath.read(device.getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(post("/api/v1/orgs/{orgId}/security-policy/trusted-devices", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"policed-1\",\"deviceId\":\"" + deviceId + "\",\"trusted\":true}")
                        .with(member(orgId, "policed-1")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Device-Id", "trusted-fp").with(member(orgId, "policed-1")))
                .andExpect(status().isOk());

        // The device's last_seen_at was stamped by the enforcement filter.
        assertThat(jdbc.queryForObject(
                "select count(*) from user_device where fingerprint = 'trusted-fp' and last_seen_at is not null",
                Integer.class)).isEqualTo(1);
    }

    private void setPolicy(UUID orgId, String subject, String body) throws Exception {
        mockMvc.perform(put("/api/v1/orgs/{orgId}/security-policy", orgId)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(member(orgId, subject)))
                .andExpect(status().isOk());
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
                + "values (?, ?, ?, 'PolRole', false, 0, now())", roleId, orgId,
                "POL_" + subject.toUpperCase().replace('-', '_'));
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}
