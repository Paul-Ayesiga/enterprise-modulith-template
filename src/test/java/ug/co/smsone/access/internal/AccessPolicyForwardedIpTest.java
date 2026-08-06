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
import ug.co.smsone.testsupport.AbstractIntegrationTest;

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
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "fwd-1");

        mockMvc.perform(put("/api/v1/orgs/{orgId}/security-policy", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ipAllowlist\":\"10.0.0.0/8\",\"requireTrustedDevice\":false}")
                        .with(member(orgId, "fwd-1")))
                .andExpect(status().isOk());

        // Our proxy vouched for 10.1.2.3 (rightmost) → inside the allowlist → admitted,
        // even though the socket peer (127.0.0.1) is not in the list.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Forwarded-For", "203.0.113.9, 10.1.2.3")
                        .with(member(orgId, "fwd-1")))
                .andExpect(status().isOk());

        // The caller forged an allowed address on the LEFT; the vouched entry is outside → refused.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .header("X-Forwarded-For", "10.1.2.3, 203.0.113.9")
                        .with(member(orgId, "fwd-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail",
                        org.hamcrest.Matchers.containsString("ip-allowlist")));

        // No header at all under declared hops: the socket peer decides (and is outside the list).
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .with(member(orgId, "fwd-1")))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }

    private void seedMember(UUID orgId, String subject) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'FwdRole', false, 0, now())", roleId, orgId,
                "FWD_" + subject.toUpperCase().replace('-', '_'));
        for (String permission : new String[] {"ORG_READ", "ORG_UPDATE", "SUBSCRIPTION_READ"}) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}
