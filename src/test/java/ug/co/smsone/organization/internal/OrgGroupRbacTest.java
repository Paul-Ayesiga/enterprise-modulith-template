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
import ug.co.smsone.testsupport.EdgeSeed;

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
        UUID orgId = seedOrg();
        UUID manager = seedPerson();
        UUID worker = seedPerson();
        // The manager holds member:read + member:role:assign + audit:read (so it can grant AUDITOR).
        UUID managerRole = seedRole(orgId, "MANAGER", "ORG_READ", "MEMBER_READ", "MEMBER_ROLE_ASSIGN", "AUDIT_READ");
        seedRole(orgId, "AUDITOR", "AUDIT_READ");
        seedRole(orgId, "PLAIN", "ORG_READ");
        seedMembership(orgId, manager, managerRole);
        seedMembership(orgId, worker, roleId(orgId, "PLAIN"));

        // The worker starts with only org:read, no audit:read.
        assertThat(authorization.permissions(worker, orgId)).containsExactly("org:read");

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/groups", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Auditors\",\"roleCode\":\"AUDITOR\"}")
                        .with(member(orgId, manager)))
                .andExpect(status().isCreated())
                .andReturn();
        String groupId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(post("/api/v1/orgs/{orgId}/groups/{id}/members", orgId, groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"" + worker + "\"}")
                        .with(member(orgId, manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.members[0]").value(worker.toString()));

        // The union is live (cache cleared on add): the worker now holds audit:read via the group.
        assertThat(authorization.permissions(worker, orgId))
                .containsExactlyInAnyOrder("org:read", "audit:read");

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/groups/{id}/members/{personId}", orgId, groupId, worker)
                        .with(member(orgId, manager)))
                .andExpect(status().isOk());
        assertThat(authorization.permissions(worker, orgId))
                .as("removal drops the conferred permission").containsExactly("org:read");
    }

    @Test
    void creatingAGroupYouCannotStaffIsRefusedAndNonMembersCannotBeGrouped() throws Exception {
        UUID orgId = seedOrg();
        UUID manager = seedPerson();
        // This manager can assign roles but does NOT hold member:remove.
        UUID managerRole = seedRole(orgId, "MGR", "ORG_READ", "MEMBER_READ", "MEMBER_ROLE_ASSIGN");
        seedRole(orgId, "SWEEPER", "MEMBER_REMOVE");
        seedMembership(orgId, manager, managerRole);

        // Group conferring SWEEPER (member:remove) — a permission the creator lacks: escalation 403.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/groups", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sweepers\",\"roleCode\":\"SWEEPER\"}")
                        .with(member(orgId, manager)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail", org.hamcrest.Matchers.containsString("member:remove")));

        // A group the creator CAN staff, then a non-member add → 404 (a group is not a way in).
        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/groups", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Readers\",\"roleCode\":\"MGR\"}")
                        .with(member(orgId, manager)))
                .andExpect(status().isCreated())
                .andReturn();
        String groupId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(post("/api/v1/orgs/{orgId}/groups/{id}/members", orgId, groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personId\":\"" + UUID.randomUUID() + "\"}")
                        .with(member(orgId, manager)))
                .andExpect(status().isNotFound());
    }

    /**
     * A token the edge can actually resolve. Both halves matter and both were missing: without
     * {@code iss} the ({@code iss}, {@code sub}) pair never reaches {@code external_identity}, so the
     * caller is nobody; and the {@code organization} claim carries the PROVIDER's org id keyed by the
     * PROVIDER's alias, not {@code organization.id} keyed by a hard-coded "acme" — resolution goes
     * through {@code external_organization}, so the claim is rebuilt from the row {@link #seedOrg}
     * seeded. A caller who resolves to neither person nor tenant holds no permissions, which is why
     * every request in this class was answered with the generic 403 rather than by the rule under test.
     */
    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, UUID personId) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return jwt().jwt(token -> token.subject(subjectOf(personId))
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }

    /**
     * {@code organization.id} is the tenant key and the row mints it, but the token names the tenant by
     * the provider's id — so the {@code external_organization} link has to exist too, and
     * {@code org_role.org_id} / {@code membership.org_id} are real FKs to {@code organization(id)} now.
     */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "groups-" + UUID.randomUUID());
    }

    /** A person the edge can resolve a token to; {@code membership.person_id} is that person's id. */
    private UUID seedPerson() {
        return EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
    }

    /** The subject {@link #seedPerson} linked this person by — unique per (provider, issuer, subject). */
    private String subjectOf(UUID personId) {
        return jdbc.queryForObject(
                "select external_subject from external_identity where person_id = ?", String.class, personId);
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

    private void seedMembership(UUID orgId, UUID personId, UUID roleId) {
        jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
    }
}
