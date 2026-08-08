package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ug.co.smsone.organization.internal.OrgRbacFixtures.MANAGER_PERMISSIONS;
import static ug.co.smsone.organization.internal.OrgRbacFixtures.VIEWER_PERMISSIONS;
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

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.ProviderOrgMembership;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The org RBAC surface end-to-end over HTTP: {@code hasPermission(#orgId, ...)} allow/deny per role,
 * cross-org and no-active-org denial, provisioning orchestration on invite (Keycloak mocked), and
 * last-owner protection. Complements {@link OrgRbacAuthorityTest} (port-level matrix).
 *
 * <p>{@code OWNER} is the only seeded role; MANAGER and VIEWER are built here the way an owner builds
 * them through the API. Nothing in the request path reads a role code, so the codes are arbitrary —
 * {@link #aRoleNamedAdminIsJustAnotherCustomRole()} pins that.
 *
 * <h2>Where the tenant axis comes from, and why only half this class declares one (ADR 0010 §3.4)</h2>
 *
 * <p>Every {@code mockMvc.perform(...)} below needs nothing: {@code CurrentUserFilter} resolves the
 * token, pins the organization it names, and restores what the test thread had on the way out — which
 * is the production behaviour this class exists to exercise, so borrowing an axis for it would be
 * testing the harness instead.
 *
 * <p>What DOES need one is everything the test does around those requests. The harness pins PLATFORM,
 * and the seed and the assertions reach {@code org_role}, {@code role_permission} and
 * {@code membership} — tenant-tier, addressed bare, so on that axis they resolve to nothing rather than
 * to the wrong rows. Those sites say {@link #callInOrg} and mean it. The rows read to BUILD a token —
 * {@code external_organization}, {@code external_identity} — are platform-tier and resolve on the
 * harness pin unchanged, which is the same division {@code CurrentUserFilter} itself makes.
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

    @Autowired
    private JdbcTemplate jdbc; // the only view that still sees soft-deleted rows

    @MockitoBean
    private KeycloakOrgAdminGateway keycloakOrg; // no live Keycloak in the RBAC matrix

    @MockitoBean
    private PersonProvisioning personProvisioning; // identity port used by MemberService.invite

    @MockitoBean
    private ProviderOrgMembership providerOrgMembership; // identity port: attach/detach at Keycloak

    private UUID orgId;
    private UUID owner;
    private UUID manager;
    private UUID viewer;

    @BeforeEach
    void seed() {
        // organization.id is the tenant key, but a token names the tenant by the PROVIDER's org id, so
        // the external_organization link has to exist beside the row — EdgeSeed writes both and returns
        // organization.id. The register()/save() pair left the link out, which meant no token could
        // resolve this tenant and the invite path's requireProviderOrgId had nothing to hand Keycloak.
        orgId = EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
        // org_role and role_permission are the tenant's; EdgeSeed.organization wrote platform rows and
        // needed no axis of its own.
        TenantContext.runAs(orgId, () -> roleSeeder.seedSystemRoles(orgId)); // seeds OWNER, and only OWNER
        owner = attachToSeededOwner(newPerson()); // the fixtures pin for themselves — see OrgRbacFixtures
        manager = attachToNewRole(newPerson(), "MANAGER", MANAGER_PERMISSIONS);
        viewer = attachToNewRole(newPerson(), "VIEWER", VIEWER_PERMISSIONS);
    }

    /**
     * Reads THIS organization's own tables — {@code org_role}, {@code membership},
     * {@code role_permission} — from the test thread, which the harness pinned to PLATFORM. They are
     * tenant-tier and unqualified (ADR 0010 §2), so without this they fail with
     * {@code relation "org_role" does not exist} rather than returning the wrong rows.
     */
    private <T> T callInOrg(java.util.function.Supplier<T> work) {
        return TenantContext.callAs(orgId, work);
    }

    /**
     * A person row plus its {@code external_identity} link. {@code membership.person_id} is a person id
     * now, not a subject string, and the edge will only resolve a token whose subject is linked — so a
     * bare {@code UUID.randomUUID()} member is one nobody can authenticate as.
     */
    private UUID newPerson() {
        return EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
    }

    private UUID attachToSeededOwner(UUID personId) {
        return OrgRbacFixtures.attachToSeededOwner(roles, memberships, orgId, personId);
    }

    private UUID attachToNewRole(UUID personId, String code, Set<Permission> permissions) {
        return OrgRbacFixtures.attachToNewRole(roles, memberships, orgId, personId, code, permissions);
    }

    /**
     * A JWT the edge resolves to this person in this tenant. Three things have to line up and none of
     * them is the person id: {@code iss} (without it {@code CurrentUserProvider} never consults
     * {@code external_identity} at all and the caller is nobody), the subject the person was LINKED by,
     * and an {@code organization} claim carrying the provider's org id under the provider's alias —
     * both read back from the row {@link EdgeSeed#organization} seeded, since resolution runs through
     * {@code external_organization} and matching the local slug would be a tenant crossing.
     */
    private JwtRequestPostProcessor token(UUID personId, UUID activeOrg) {
        String subject = subjectOf(personId);
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                activeOrg);
        return jwt().jwt(jwt -> jwt.subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("email", personId + "@smsone.co.ug")
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }

    /** The subject {@link #newPerson} linked this person by — unique per (provider, issuer, subject). */
    private String subjectOf(UUID personId) {
        return jdbc.queryForObject(
                "select external_subject from external_identity where person_id = ?", String.class, personId);
    }

    @Test
    void memberCanReadButCannotInvite() throws Exception {
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(viewer, orgId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .with(token(viewer, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@smsone.co.ug\",\"roleCode\":\"VIEWER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        then(personProvisioning).should(never()).provision(any()); // denied before any provisioning
    }

    @Test
    void ownerInviteProvisionsAcrossModulesAndCreatesMembership() throws Exception {
        UUID newPersonId = UUID.randomUUID();
        given(personProvisioning.provision(any(ProvisionRequest.class)))
                .willReturn(new ProvisionedPerson(newPersonId, "new@smsone.co.ug", false));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@smsone.co.ug\",\"givenName\":\"New\",\"roleCode\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("member"))
                .andExpect(jsonPath("$.data.attributes.roleCode").value("VIEWER"))
                .andExpect(jsonPath("$.data.attributes.personId").value(newPersonId.toString()));

        // Attaching at the provider now goes through identity, which owns the subject the call needs.
        then(providerOrgMembership).should().attach(eq(newPersonId), any());
        org.junit.jupiter.api.Assertions.assertTrue(
                callInOrg(() -> memberships.findByOrgIdAndPersonId(orgId, newPersonId)).isPresent());
    }

    @Test
    void memberCannotCreateRoleButOwnerCan() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(viewer, orgId))
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
    void aCallerCannotGrantAPermissionItDoesNotHold() throws Exception {
        // MANAGER holds everything except org:delete — it must not be able to mint a role carrying it.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(manager, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SUPER\",\"name\":\"Super\",\"permissions\":[\"org:read\",\"org:delete\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        // ...but a role built only from permissions MANAGER holds is fine.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(manager, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SUPPORT\",\"name\":\"Support\",\"permissions\":[\"member:read\",\"member:invite\"]}"))
                .andExpect(status().isCreated());
    }

    /**
     * The end-to-end shape of the dynamic-role model: a fresh org ships exactly one role, the owner
     * mints AUDITOR, assigns it, and the holder gets precisely what was granted — nothing more.
     */
    @Test
    void ownerMintsARoleAndItsHolderGetsExactlyThosePermissions() throws Exception {
        mockMvc.perform(get("/api/v1/orgs/{orgId}/roles", orgId).with(token(owner, orgId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.attributes.systemRole == true)].attributes.code",
                        org.hamcrest.Matchers.contains(Role.OWNER_CODE)));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUDITOR\",\"name\":\"Auditor\","
                                + "\"permissions\":[\"org:read\",\"member:read\"]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/v1/orgs/{orgId}/members/{personId}/role", orgId, viewer)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"AUDITOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.roleCode").value("AUDITOR"));

        // Granted: member:read. Not granted: member:invite, org:update — 403 on both.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(viewer, orgId)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/orgs/{orgId}", orgId)
                        .with(token(viewer, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * {@code ADMIN} used to be a seeded system role with near-owner permissions. It is now an ordinary
     * code, and creating a role under that name grants nothing the permission set does not — the check
     * this pins is that no route anywhere resolves authority from a code other than OWNER.
     */
    @Test
    void aRoleNamedAdminIsJustAnotherCustomRole() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ADMIN\",\"name\":\"Administrator\","
                                + "\"permissions\":[\"org:read\",\"member:read\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.systemRole").value(false));

        mockMvc.perform(put("/api/v1/orgs/{orgId}/members/{personId}/role", orgId, viewer)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        // The name buys nothing: still no member:invite, still no org:update.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .with(token(viewer, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@smsone.co.ug\",\"roleCode\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/orgs/{orgId}", orgId)
                        .with(token(viewer, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * A custom role holding {@code member:invite} can invite — authority tracks the permission, not the
     * name. RECRUITER must also hold everything VIEWER holds: inviting someone INTO a role is granting
     * that role's permissions, so {@link PermissionEscalationGuard} requires the inviter to hold them
     * all. Without that, {@code member:invite} alone would be a path to handing out OWNER.
     */
    @Test
    void aCustomRoleCarryingMemberInviteCanInvite() throws Exception {
        Set<Permission> recruiterPermissions = EnumSet.copyOf(VIEWER_PERMISSIONS);
        recruiterPermissions.add(Permission.MEMBER_INVITE);
        UUID recruiter = attachToNewRole(newPerson(), "RECRUITER", recruiterPermissions);
        given(personProvisioning.provision(any(ProvisionRequest.class)))
                .willReturn(new ProvisionedPerson(UUID.randomUUID(), "hire@smsone.co.ug", false));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .with(token(recruiter, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"hire@smsone.co.ug\",\"roleCode\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void anOrgRoleCodeCannotBorrowThePlatformVocabulary() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"platform-admin\",\"name\":\"Sneaky\","
                                + "\"permissions\":[\"org:read\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/code"));
    }

    @Test
    void unknownPermissionCodeOnRoleCreateIs422() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BADROLE\",\"name\":\"Bad\",\"permissions\":[\"org:teleport\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/permissions"));
    }

    @Test
    void systemRoleUpdateIsForbidden() throws Exception {
        UUID ownerRoleId = callInOrg(() ->
                roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow().getId());
        mockMvc.perform(put("/api/v1/orgs/{orgId}/roles/{roleId}", orgId, ownerRoleId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked\",\"permissions\":[\"org:read\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberCannotUpdateOrgButOwnerCan() throws Exception {
        mockMvc.perform(patch("/api/v1/orgs/{orgId}", orgId)
                        .with(token(viewer, orgId))
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

    /**
     * A fully resolvable person — {@code iss} and their linked subject are both present — carrying no
     * {@code organization} claim. The token is denied for naming no tenant, which is the only reason
     * this test is allowed to be about: a token missing {@code iss} would 403 for being nobody, and
     * would pass while saying nothing about the org scoping.
     */
    @Test
    void tokenWithNoActiveOrgIsDenied() throws Exception {
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId)
                        .with(jwt().jwt(jwt -> jwt.subject(subjectOf(owner)).claim("iss", EdgeSeed.ISSUER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aNonOwnerCannotSelfPromoteToOwner() throws Exception {
        // The escalation guard applies to role ASSIGNMENT too: handing yourself OWNER would grant
        // org:delete, which MANAGER does not hold. Without this, member:role:assign == OWNER.
        mockMvc.perform(put("/api/v1/orgs/{orgId}/members/{personId}/role", orgId, manager)
                        .with(token(manager, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"OWNER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        org.junit.jupiter.api.Assertions.assertEquals(
                callInOrg(() -> roles.findByOrgIdAndCode(orgId, "MANAGER").orElseThrow().getId()),
                callInOrg(() -> memberships.findByOrgIdAndPersonId(orgId, manager).orElseThrow().getRoleId()));
    }

    @Test
    void aNonOwnerCannotInviteAnOwner() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .with(token(manager, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"boss@smsone.co.ug\",\"roleCode\":\"OWNER\"}"))
                .andExpect(status().isForbidden());

        then(personProvisioning).should(never()).provision(any()); // rejected before provisioning
    }

    @Test
    void ownerCanPromoteAMemberToOwner() throws Exception {
        mockMvc.perform(put("/api/v1/orgs/{orgId}/members/{personId}/role", orgId, viewer)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"OWNER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.roleCode").value("OWNER"));
    }

    @Test
    void duplicateAliasCreateIsConflictNotAdoption() throws Exception {
        Organization existing = organizations.findById(orgId).orElseThrow();

        mockMvc.perform(post("/api/v1/orgs")
                        .with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"" + existing.getAlias() + "\",\"name\":\"Impostor\","
                                + "\"ownerEmail\":\"evil@smsone.co.ug\"}"))
                .andExpect(status().isConflict());

        then(personProvisioning).should(never()).provision(any()); // no second OWNER ever provisioned
        then(keycloakOrg).should(never()).createOrganization(any(), any());
    }

    @Test
    void createRefusesToAdoptAnExistingKeycloakOrg() throws Exception {
        // Local projection absent but the alias exists Keycloak-side (e.g. a concurrent create won):
        // strict create must 409, never silently attach a new OWNER to someone else's org.
        // The provider's org id is opaque to us and is a String, not a UUID (V11: varchar so a Google
        // customer id fits) — stubbing it as one is what made the shape assumption invisible.
        given(keycloakOrg.findOrganizationIdByAlias("taken-alias"))
                .willReturn(java.util.Optional.of("kc-org-" + UUID.randomUUID()));

        mockMvc.perform(post("/api/v1/orgs")
                        .with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"taken-alias\",\"name\":\"Impostor\","
                                + "\"ownerEmail\":\"evil@smsone.co.ug\"}"))
                .andExpect(status().isConflict());

        then(personProvisioning).should(never()).provision(any());
        then(keycloakOrg).should(never()).createOrganization(any(), any());
    }

    @Test
    void suspendIsPlatformAdminOnlyAndCutsMemberAccess() throws Exception {
        // An org OWNER is not a platform admin — suspension is out of tenant reach.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/suspend", orgId).with(token(owner, orgId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/suspend", orgId).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("SUSPENDED"));

        // Fresh subject: nothing cached, so the resolver's org-status check applies immediately.
        UUID lateJoiner = attachToSeededOwner(newPerson());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(lateJoiner, orgId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/reactivate", orgId).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("ACTIVE"));
    }

    /** A platform operator: realm role only, no org scoping — the axes are disjoint. */
    private JwtRequestPostProcessor platformAdmin() {
        return jwt().jwt(jwt -> jwt.subject("platform-admin"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    @Test
    void removingTheLastOwnerIsBlocked() throws Exception {
        mockMvc.perform(delete("/api/v1/orgs/{orgId}/members/{personId}", orgId, owner).with(token(owner, orgId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));

        then(providerOrgMembership).should(never()).detach(any(), any()); // guarded before any Keycloak call
    }

    /**
     * The case that motivated every partial index in V17, end to end over HTTP. A soft-deleted row
     * still occupies {@code (org_id, code)}; without {@code where deleted_at is null} on
     * {@code uq_org_role_org_code} this second create is a 409 against a role nobody can see, list or
     * restore — an org that once had an AUDITOR could never have one again.
     */
    @Test
    void aDeletedRoleCodeCanBeMintedAgain() throws Exception {
        createAuditorRole();
        UUID firstId = callInOrg(() -> roles.findByOrgIdAndCode(orgId, "AUDITOR").orElseThrow().getId());

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/roles/{roleId}", orgId, firstId).with(token(owner, orgId)))
                .andExpect(status().isNoContent());

        createAuditorRole();

        UUID secondId = callInOrg(() -> roles.findByOrgIdAndCode(orgId, "AUDITOR").orElseThrow().getId());
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(callInOrg(() -> jdbc.queryForObject(
                "select count(*) from org_role where org_id = ? and code = 'AUDITOR'",
                Integer.class, orgId))).isEqualTo(2); // one dead, one live
        assertThat(callInOrg(() -> jdbc.queryForObject(
                "select deleted_at is not null from org_role where id = ?",
                Boolean.class, firstId))).isTrue();
    }

    /**
     * Two consequences of hiding rather than removing, in the order an operator hits them: a removed
     * member must vanish from the listing, and must stop counting as an assignment — the role they
     * held becomes deletable, which under the old hard FK it never would have been mid-transaction.
     */
    @Test
    void aRemovedMemberLeavesTheListingAndReleasesTheirRole() throws Exception {
        createAuditorRole();
        UUID auditorId = callInOrg(() -> roles.findByOrgIdAndCode(orgId, "AUDITOR").orElseThrow().getId());
        mockMvc.perform(put("/api/v1/orgs/{orgId}/members/{personId}/role", orgId, viewer)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"AUDITOR\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/roles/{roleId}", orgId, auditorId).with(token(owner, orgId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/members/{personId}", orgId, viewer).with(token(owner, orgId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(token(owner, orgId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + owner + "')]").exists())   // listing reaches the org
                .andExpect(jsonPath("$.data[?(@.id=='" + viewer + "')]").doesNotExist());
        assertThat(callInOrg(() -> jdbc.queryForObject(
                "select count(*) from membership where org_id = ? and person_id = ?",
                Integer.class, orgId, viewer))).isEqualTo(1); // hidden, not gone

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/roles/{roleId}", orgId, auditorId).with(token(owner, orgId)))
                .andExpect(status().isNoContent());
    }

    private void createAuditorRole() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/{orgId}/roles", orgId)
                        .with(token(owner, orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUDITOR\",\"name\":\"Auditor\","
                                + "\"permissions\":[\"org:read\",\"member:read\"]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void permissionCatalogIsReadableByAnyAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v1/permissions").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("permission"))
                .andExpect(jsonPath("$.data[?(@.id=='org:delete')]").exists());
    }
}
