package ug.co.smsone.organization.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The org RBAC surface end-to-end over HTTP: {@code hasPermission(#orgId, ...)} allow/deny per role,
 * cross-org and no-active-org denial, provisioning orchestration on invite (Keycloak mocked), and
 * last-owner protection. Complements {@link OrgRbacAuthorityTest} (port-level matrix).
 */
@AutoConfigureMockMvc
class OrgRbacApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private RoleSeeder roleSeeder;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private MembershipRepository memberships;

    @MockitoBean
    private KeycloakOrgAdminGateway keycloakOrg; // no live Keycloak in the RBAC matrix

    @MockitoBean
    private UserProvisioning userProvisioning; // identity port used by MemberService.invite

    private UUID orgId;
    private String owner;
    private String admin;
    private String member;

    @BeforeEach
    void seed() {
        orgId = UUID.randomUUID();
        organizations.save(Organization.register(orgId, "acme-" + orgId, "Acme"));
        roleSeeder.seedSystemRoles(orgId);
        owner = attach("owner-" + UUID.randomUUID(), "OWNER");
        admin = attach("admin-" + UUID.randomUUID(), "ADMIN");
        member = attach("member-" + UUID.randomUUID(), "MEMBER");
    }

    private String attach(String subject, String roleCode) {
        Role role = roles.findByOrgIdAndCode(orgId, roleCode).orElseThrow();
        memberships.save(Membership.create(orgId, subject, role.getId(), roleCode));
        return subject;
    }

    /** A JWT scoped to {@code activeOrg} (alias-keyed 'organization' claim) for the given subject. */
    private JwtRequestPostProcessor token(String subject, UUID activeOrg) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .claim("email", subject + "@smsone.co.ug")
                .claim("organization", Map.of("acme", Map.of("id", activeOrg.toString()))));
    }

    @Test
    void memberCanReadButCannotInvite() throws Exception {
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(member, orgId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .with(token(member, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@smsone.co.ug\",\"roleCode\":\"MEMBER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        then(userProvisioning).should(never()).provision(any()); // denied before any provisioning
    }

    @Test
    void ownerInviteProvisionsAcrossModulesAndCreatesMembership() throws Exception {
        String newSubject = "kc-" + UUID.randomUUID();
        given(userProvisioning.provision(any(ProvisionRequest.class)))
                .willReturn(new ProvisionedUser(newSubject, "new@smsone.co.ug", false));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@smsone.co.ug\",\"firstName\":\"New\",\"roleCode\":\"MEMBER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("member"))
                .andExpect(jsonPath("$.data.attributes.roleCode").value("MEMBER"))
                .andExpect(jsonPath("$.data.attributes.subject").value(newSubject));

        then(keycloakOrg).should().addMember(eq(orgId), eq(newSubject)); // linked in Keycloak too
        org.junit.jupiter.api.Assertions.assertTrue(
                memberships.findByOrgIdAndUserSubject(orgId, newSubject).isPresent());
    }

    @Test
    void memberCannotCreateRoleButOwnerCan() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(member, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUDITOR\",\"name\":\"Auditor\",\"permissions\":[\"org:read\"]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUDITOR\",\"name\":\"Auditor\",\"permissions\":[\"org:read\",\"member:read\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.code").value("AUDITOR"))
                .andExpect(jsonPath("$.data.attributes.systemRole").value(false));
    }

    @Test
    void adminCannotGrantAPermissionItDoesNotHold() throws Exception {
        // ADMIN holds everything except org:delete — it must not be able to mint a role carrying it.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(admin, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SUPER\",\"name\":\"Super\",\"permissions\":[\"org:read\",\"org:delete\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        // ...but a role built only from permissions ADMIN holds is fine.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(admin, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SUPPORT\",\"name\":\"Support\",\"permissions\":[\"member:read\",\"member:invite\"]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void unknownPermissionCodeOnRoleCreateIs422() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BADROLE\",\"name\":\"Bad\",\"permissions\":[\"org:teleport\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/permissions"));
    }

    @Test
    void systemRoleUpdateIsForbidden() throws Exception {
        UUID ownerRoleId = roles.findByOrgIdAndCode(orgId, "OWNER").orElseThrow().getId();
        mockMvc.perform(put("/api/v1/orgs/{orgId}/roles/{roleId}", orgId, ownerRoleId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked\",\"permissions\":[\"org:read\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberCannotUpdateOrgButOwnerCan() throws Exception {
        mockMvc.perform(patch("/api/v1/orgs/{orgId}", orgId)
                        .with(token(member, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/orgs/{orgId}", orgId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.name").value("Renamed"));
    }

    @Test
    void crossOrgAccessIsDeniedBeforeAnyDbHit() throws Exception {
        UUID otherOrg = UUID.randomUUID();
        // Owner in `orgId`, but the token is scoped to `orgId` while the path targets a different org.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", otherOrg).with(token(owner, orgId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenWithNoActiveOrgIsDenied() throws Exception {
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(jwt().jwt(jwt -> jwt.subject(owner))))
                .andExpect(status().isForbidden());
    }

    @Test
    void removingTheLastOwnerIsBlocked() throws Exception {
        mockMvc.perform(delete("/api/v1/orgs/{orgId}/members/{subject}", orgId, owner).with(token(owner, orgId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));

        then(keycloakOrg).should(never()).removeMember(any(), any()); // guarded before any Keycloak call
    }

    @Test
    void permissionCatalogIsReadableByAnyAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v1/permissions").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("permission"))
                .andExpect(jsonPath("$.data[?(@.id=='org:delete')]").exists());
    }
}
