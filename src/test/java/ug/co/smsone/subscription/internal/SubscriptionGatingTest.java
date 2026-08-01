package ug.co.smsone.subscription.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The entitlement gates through the REAL paths: a capped plan refuses the invite that would exceed
 * it and the exchange feature it lacks — with "upgrade"-shaped 403s, before anything is
 * provisioned — and an admin plan change bites the next check (cache evicted via
 * SubscriptionChanged, asserted with Awaitility because the listener is async). Also pins the two
 * REST surfaces and the seeded catalog.
 */
@AutoConfigureMockMvc
class SubscriptionGatingTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aCappedPlanRefusesTheGatesUntilUpgraded() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedInviter(orgId, "gating-inviter");
        seedTinyPlan();

        mockMvc.perform(put("/api/v1/admin/orgs/{orgId}/subscription", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\": \"TINY\"}")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.planCode").value("TINY"));

        // The org already has 1 member (the inviter); TINY caps members.max at 1. The gate runs
        // BEFORE provisioning, so the refusal leaves no half-created identity (and touches no IdP).
        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"more@x.com\",\"firstName\":\"M\",\"lastName\":\"X\",\"roleCode\":\"GATED\"}")
                        .with(member(orgId, "gating-inviter")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail",
                        org.hamcrest.Matchers.containsString("Upgrade the plan")));

        // TINY carries no exchange.enabled either: the platform itself is gated off.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/exchange/exports", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handler\":\"org-members\",\"format\":\"CSV\"}")
                        .with(member(orgId, "gating-inviter")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail",
                        org.hamcrest.Matchers.containsString("does not include")));

        mockMvc.perform(put("/api/v1/admin/orgs/{orgId}/subscription", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\": \"PRO\"}")
                        .with(admin()))
                .andExpect(status().isOk());

        // The evictor is an async listener: the upgrade must bite, promptly, without a TTL wait.
        // Proven through the same gate that refused above (202 = past the feature gate).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(post("/api/v1/orgs/{orgId}/exchange/exports", orgId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"handler\":\"org-members\",\"format\":\"CSV\"}")
                                .with(member(orgId, "gating-inviter")))
                        .andExpect(status().isAccepted()));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId)
                        .with(member(orgId, "gating-inviter")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.planCode").value("PRO"))
                .andExpect(jsonPath("$.data.attributes.entitlements['members.max']").value(250));
    }

    @Test
    void theSeededCatalogIsDiscoverableToSupport() throws Exception {
        mockMvc.perform(get("/api/v1/admin/plans")
                        .with(jwt().jwt(t -> t.subject("support-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("FREE"))
                .andExpect(jsonPath("$.data[2].id").value("ENTERPRISE"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }

    /** TINY: members capped at 1, no exchange — the plan that makes every gate observable. */
    private void seedTinyPlan() {
        UUID planId = UUID.randomUUID();
        jdbc.update("insert into plan (id, code, name, rank, version, created_at) "
                + "values (?, 'TINY', 'Tiny', 9, 0, now()) on conflict (code) do nothing", planId);
        UUID actual = jdbc.queryForObject("select id from plan where code = 'TINY'", UUID.class);
        jdbc.update("insert into plan_entitlement (plan_id, ent_key, limit_value) "
                + "values (?, 'members.max', 1) on conflict do nothing", actual);
    }

    private void seedInviter(UUID orgId, String subject) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, 'GATED', 'Gated', false, 0, now())", roleId, orgId);
        for (String permission : new String[] {"ORG_READ", "MEMBER_READ", "MEMBER_INVITE"}) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}
