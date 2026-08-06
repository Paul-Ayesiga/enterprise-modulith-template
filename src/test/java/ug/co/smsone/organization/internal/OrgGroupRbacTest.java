package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import ug.co.smsone.shared.security.OrgAuthorization;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Groups union their role into a member's effective permissions: a direct MEMBER who joins an
 * AUDITOR group resolves MEMBER ∪ AUDITOR, and removing them drops it back — with the permission
 * cache cleared on every group mutation. Handing out a group role passes the escalation guard, and
 * only existing members can be grouped.
 */
@AutoConfigureMockMvc
class OrgGroupRbacTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrgAuthorization authorization;

    @Test
    void aGroupUnionsItsRoleIntoEveryMembersPermissions() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedOrg(orgId);
        // The manager holds member:read + member:role:assign + audit:read (so it can grant AUDITOR).
        UUID managerRole = seedRole(orgId, "MANAGER", "ORG_READ", "MEMBER_READ", "MEMBER_ROLE_ASSIGN", "AUDIT_READ");
        seedRole(orgId, "AUDITOR", "AUDIT_READ");
        seedRole(orgId, "PLAIN", "ORG_READ");
        seedMembership(orgId, "manager-1", managerRole);
        seedMembership(orgId, "worker-1", roleId(orgId, "PLAIN"));

        // worker-1 starts with only org:read, no audit:read.
        assertThat(authorization.permissions("worker-1", orgId)).containsExactly("org:read");

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/groups", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Auditors\",\"roleCode\":\"AUDITOR\"}")
                        .with(member(orgId, "manager-1")))
                .andExpect(status().isCreated())
                .andReturn();
        String groupId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(post("/api/v1/orgs/{orgId}/groups/{id}/members", orgId, groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"worker-1\"}")
                        .with(member(orgId, "manager-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.members[0]").value("worker-1"));

        // The union is live (cache cleared on add): worker-1 now holds audit:read via the group.
        assertThat(authorization.permissions("worker-1", orgId))
                .containsExactlyInAnyOrder("org:read", "audit:read");

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/groups/{id}/members/{subject}", orgId, groupId, "worker-1")
                        .with(member(orgId, "manager-1")))
                .andExpect(status().isOk());
        assertThat(authorization.permissions("worker-1", orgId))
                .as("removal drops the conferred permission").containsExactly("org:read");
    }

    @Test
    void creatingAGroupYouCannotStaffIsRefusedAndNonMembersCannotBeGrouped() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedOrg(orgId);
        // This manager can assign roles but does NOT hold member:remove.
        UUID managerRole = seedRole(orgId, "MGR", "ORG_READ", "MEMBER_READ", "MEMBER_ROLE_ASSIGN");
        seedRole(orgId, "SWEEPER", "MEMBER_REMOVE");
        seedMembership(orgId, "mgr-2", managerRole);

        // Group conferring SWEEPER (member:remove) — a permission the creator lacks: escalation 403.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/groups", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sweepers\",\"roleCode\":\"SWEEPER\"}")
                        .with(member(orgId, "mgr-2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("member:remove")));

        // A group the creator CAN staff, then a non-member add → 404 (a group is not a way in).
        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/groups", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Readers\",\"roleCode\":\"MGR\"}")
                        .with(member(orgId, "mgr-2")))
                .andExpect(status().isCreated())
                .andReturn();
        String groupId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(post("/api/v1/orgs/{orgId}/groups/{id}/members", orgId, groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"stranger\"}")
                        .with(member(orgId, "mgr-2")))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }

    private void seedOrg(UUID orgId) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
    }

    private UUID seedRole(UUID orgId, String code, String... permissions) {
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, ?, false, 0, now())", roleId, orgId, code, code);
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        return roleId;
    }

    private UUID roleId(UUID orgId, String code) {
        return jdbc.queryForObject("select id from org_role where org_id = ? and code = ?", UUID.class, orgId, code);
    }

    private void seedMembership(UUID orgId, String subject, UUID roleId) {
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}
