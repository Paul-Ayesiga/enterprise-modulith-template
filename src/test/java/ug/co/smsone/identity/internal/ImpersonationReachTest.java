package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.admin;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.support;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * What an open session actually reaches. {@link ImpersonationApiTest} pins who may open one; this
 * pins what the {@code X-Impersonate} header then does to a request — and, just as importantly, what
 * it does not.
 *
 * <p>The property the whole feature rests on is asserted in
 * {@link #supportReachesTenantDataThroughASessionAndTheAdminSurfaceOnlyOutsideOne()}: the same token,
 * on the same connection, reaches tenant data <em>only</em> with the header and the platform admin
 * surface <em>only</em> without it. The impersonated principal carries no authority at all, which is
 * simultaneously why org permissions still resolve (they come from the database, for the target) and
 * why {@code /api/v1/admin/**} is unreachable from inside a session.
 *
 * <p>The organization fixture is inserted as SQL rather than through the organization module's own
 * repositories: those are package-private in {@code organization.internal}, and the Keycloak gateway
 * this test must mock is package-private here. Same real Postgres either way — see
 * {@code SoftDeletePurgeJobIntegrationTest} for the same trade.
 */
@AutoConfigureMockMvc
class ImpersonationReachTest extends AbstractIntegrationTest {

    private static final String SESSIONS = "/api/v1/admin/impersonations";
    private static final String MEMBERS = "/api/v1/orgs/{orgId}/members";
    private static final String ROLES = "/api/v1/orgs/{orgId}/roles";
    private static final String WEBHOOKS = "/api/v1/orgs/{orgId}/webhooks";
    private static final String PLATFORM_USERS = "/api/v1/admin/users";
    private static final String HEADER = "X-Impersonate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PersonRepository persons;

    @Autowired
    private ExternalIdentityRepository identities;

    @Autowired
    private PersonResolver resolver;

    @Autowired
    private ImpersonationSessionRepository sessions;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private KeycloakUserAdminGateway keycloak; // the tier guard's one remote call

    private UUID orgId;
    private UUID member;

    @BeforeEach
    void seedTenant() {
        orgId = EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
        member = provisionedPerson();
        seedOrgMembership(orgId, member, Permission.ORG_READ, Permission.MEMBER_READ,
                Permission.ROLE_READ, Permission.ROLE_CREATE, Permission.WEBHOOK_MANAGE);
    }

    @Test
    void supportReachesTenantDataThroughASessionAndTheAdminSurfaceOnlyOutsideOne() throws Exception {
        UUID operator = provisionedPerson();
        UUID session = openSession(support(operator), readOnly(member, orgId));

        // Platform authority is not an org permission — the axes never intersect, so the operator's own
        // token cannot read a tenant's member list no matter which tier it holds.
        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PLATFORM_USERS).with(support(operator)))
                .andExpect(status().isOk());

        // Same token, same tier, one header: now it is the member, with the member's permissions.
        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + member + "')]").exists());

        // ...and the platform tier does not travel into the session. THIS is what makes impersonation
        // a reach into one tenant rather than a second, unaudited way to be an operator.
        mockMvc.perform(get(PLATFORM_USERS).with(support(operator)).header(HEADER, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        // Including the endpoint that mints sessions: no session can open another.
        mockMvc.perform(post(SESSIONS).with(support(operator)).header(HEADER, session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readOnly(provisionedPerson(), orgId)))
                .andExpect(status().isForbidden());
    }

    /**
     * Read-only is enforced on the HTTP method, before the endpoint is ever reached — so the denial is
     * the session's mode, not a missing permission. The WRITE half of the test is what proves that: the
     * same operator, the same target, the same request body, and a 201.
     */
    @Test
    void aReadOnlySessionRefusesAnUnsafeMethodAndNamesTheHeaderThatCausedIt() throws Exception {
        UUID operator = provisionedPerson();
        UUID readOnly = openSession(support(operator), readOnly(member, orgId));
        String code = roleCode();

        mockMvc.perform(post(ROLES, orgId).with(support(operator)).header(HEADER, readOnly)
                        .contentType(MediaType.APPLICATION_JSON).content(createRole(code)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.errors[0].source.header").value(HEADER));
        assertThat(roleExists(orgId, code)).isFalse();

        // HEAD is a GET without a body: safe, and refusing it would break existence checks for nothing.
        mockMvc.perform(head(MEMBERS, orgId).with(support(operator)).header(HEADER, readOnly))
                .andExpect(status().isOk());

        UUID writer = provisionedPerson();
        UUID writable = openSession(admin(writer), write(member, orgId));
        mockMvc.perform(post(ROLES, orgId).with(admin(writer)).header(HEADER, writable)
                        .contentType(MediaType.APPLICATION_JSON).content(createRole(code)))
                .andExpect(status().isCreated());
        assertThat(roleExists(orgId, code)).isTrue();
    }

    /**
     * The attribution inverts, and only here. Everything else the request writes describes the account
     * as it now looks and so records the target ({@code created_by}, the membership rows, the
     * rate-limit bucket); {@code audit_log.actor_person_id} records who made it look that way.
     */
    @Test
    void anAuditRowFromInsideASessionNamesTheOperatorAndTheIdentityTheyWore() throws Exception {
        UUID operator = provisionedPerson();
        UUID session = openSession(admin(operator), write(member, orgId));
        String code = roleCode();

        mockMvc.perform(post(ROLES, orgId).with(admin(operator)).header(HEADER, session)
                        .contentType(MediaType.APPLICATION_JSON).content(createRole(code)))
                .andExpect(status().isCreated());

        Map<String, Object> row = auditRow("organization.role_created", code);
        assertThat(row.get("actor_person_id")).isEqualTo(operator);        // the human answerable
        assertThat(row.get("on_behalf_of_person_id")).isEqualTo(member);    // the identity they wore
        assertThat(row.get("actor_person_id")).isNotEqualTo(row.get("on_behalf_of_person_id"));
        assertThat(row.get("impersonation_id")).isEqualTo(session); // correlates to the stated reason

        // The other half of the invariant, and the half nothing else pins: the row the request created
        // records the TARGET. A well-meaning change that made created_by name the accountable operator
        // would leave every audit assertion above passing.
        assertThat(jdbc.queryForObject("select created_by from org_role where org_id = ? and code = ?",
                UUID.class, orgId, code))
                .as("only audit_log inverts attribution")
                .isEqualTo(member);

        // The same operator's own request, made outside the session, keeps the plain shape: one actor,
        // no second identity. A non-null on_behalf_of is therefore exactly "done inside someone else's
        // account", with no further interpretation needed.
        Map<String, Object> opened = auditRow("platform.impersonation_started", member.toString());
        assertThat(opened.get("actor_person_id")).isEqualTo(operator);
        assertThat(opened.get("on_behalf_of_person_id")).isNull();
        assertThat(opened.get("impersonation_id")).isNull();

        mockMvc.perform(get("/api/v1/audit").param("action", "organization.role_created")
                        .param("org", orgId.toString()).with(support(provisionedPerson())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.actorPersonId").value(operator.toString()))
                .andExpect(jsonPath("$.data[0].attributes.onBehalfOfPersonId").value(member.toString()))
                .andExpect(jsonPath("$.data[0].attributes.impersonationId").value(session.toString()));
    }

    /** The guarantee the feature is sold on: no cached session keeps the reach open past its end. */
    @Test
    void endingASessionDeniesTheVeryNextRequest() throws Exception {
        UUID operator = provisionedPerson();
        UUID session = openSession(support(operator), readOnly(member, orgId));

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isOk());

        mockMvc.perform(delete(SESSIONS + "/{id}", session).with(support(operator)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].source.header").value(HEADER));
    }

    /**
     * Expiry is decided on read, which is why there is no sweep job to raise a {@code _expired} event —
     * and why this test asserts {@code ended_at} is still null. The session was never touched by
     * anything; it simply stopped resolving.
     */
    @Test
    void anExpiredSessionDeniesWithoutAnySweepJobHavingRun() throws Exception {
        UUID operator = provisionedPerson();
        UUID session = openSession(support(operator), readOnly(member, orgId));

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isOk());

        jdbc.update("update impersonation_session set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), session);

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isForbidden());
        assertThat(sessions.findById(session).orElseThrow().getEndedAt())
                .as("nothing swept it — expiry is a read-time decision").isNull();
    }

    /**
     * A session id is not a bearer token. It is pinned to the operator it was issued to, so one copied
     * out of a log line is worthless to whoever copied it — including an operator holding a HIGHER
     * tier, which is the case that would otherwise look like a reasonable escalation.
     */
    @Test
    void aSessionIdPresentedByADifferentActorIsRejected() throws Exception {
        UUID owner = provisionedPerson();
        UUID session = openSession(support(owner), readOnly(member, orgId));

        mockMvc.perform(get(MEMBERS, orgId).with(admin(provisionedPerson())).header(HEADER, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        mockMvc.perform(get(MEMBERS, orgId).with(support(owner)).header(HEADER, session))
                .andExpect(status().isOk()); // still live for the operator it belongs to
    }

    /**
     * The tier that authorized the session is re-asked for on every request it carries. A session lives
     * up to thirty minutes; if revoking {@code platform-support} only stopped the <em>next</em> open, an
     * offboarded operator would keep the reach they already hold until the clock ran out — and the
     * endpoint that mints sessions would be the only thing their revocation actually reached.
     */
    @Test
    void anOperatorWhoLosesTheirPlatformTierLosesTheSessionTheyHold() throws Exception {
        UUID operator = provisionedPerson();
        UUID session = openSession(support(operator), readOnly(member, orgId));

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isOk());

        // Same person, same session id, a token that no longer carries any platform role.
        mockMvc.perform(get(MEMBERS, orgId)
                        .with(jwt().jwt(token -> token.subject(ImpersonationFixtures.subjectOf(operator))
                                .claim("iss", EdgeSeed.ISSUER)))
                        .header(HEADER, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].source.header").value(HEADER));
    }

    /** WRITE was authorized against platform-admin, so losing that tier must cost the writes first. */
    @Test
    void demotingAWriteOperatorRefusesTheWriteCapableSession() throws Exception {
        UUID operator = provisionedPerson();
        UUID session = openSession(admin(operator), write(member, orgId));

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].source.header").value(HEADER));
        mockMvc.perform(get(MEMBERS, orgId).with(admin(operator)).header(HEADER, session))
                .andExpect(status().isOk()); // still theirs at the tier it was opened with
    }

    /**
     * Offboarding an operator is a local account change, and under impersonation nothing downstream ever
     * sees them: the swap happens at {@code @Order(-2)}, so {@code ProvisioningGateFilter} evaluates the
     * target. Without a check inside the lookup, the header would turn a disabled operator's blocked
     * request into a permitted one — and every audit row it wrote would name a disabled account as actor.
     */
    @Test
    void disablingTheOperatorsOwnAccountDeniesTheVeryNextImpersonatedRequest() throws Exception {
        UUID operator = provisionedPerson();
        UUID session = openSession(support(operator), readOnly(member, orgId));

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isOk());

        Person account = persons.findById(operator).orElseThrow();
        account.disable(Instant.now());
        persons.save(account);

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].source.header").value(HEADER));
    }

    /**
     * The target's state is re-read here rather than delegated to {@code ProvisioningGateFilter}: that
     * gate is a documented kill switch ({@code app.provisioning.gate-enabled}, off in this profile), and
     * a guarantee this feature is sold on cannot depend on a switch someone else owns. Erasing an
     * account has to cut the reach into it immediately, not at the end of the TTL.
     */
    @Test
    void deletingTheTargetMidSessionDeniesTheVeryNextImpersonatedRequest() throws Exception {
        UUID operator = provisionedPerson();
        UUID session = openSession(support(operator), readOnly(member, orgId));

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isOk());

        persons.delete(persons.findById(member).orElseThrow()); // soft delete — the row survives, the access does not

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].source.header").value(HEADER));
    }

    /**
     * A subscription outlives the session that created it — it keeps receiving the org's events, signed
     * with a secret the response showed once — so it is the write most worth attributing. Same assertion
     * as the role_created case: the row names the operator, the identity they wore, and the session
     * carrying the reason.
     */
    @Test
    void aWebhookCreatedInsideASessionNamesTheOperatorAndTheSession() throws Exception {
        UUID operator = provisionedPerson();
        UUID session = openSession(admin(operator), write(member, orgId));

        String created = mockMvc.perform(post(WEBHOOKS, orgId).with(admin(operator)).header(HEADER, session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://hooks.example.com/x\",\"events\":[\"org.member.added\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> row = auditRow("webhooks.subscription_created",
                JsonPath.read(created, "$.data.id"));
        assertThat(row.get("actor_person_id")).isEqualTo(operator);
        assertThat(row.get("on_behalf_of_person_id")).isEqualTo(member);
        assertThat(row.get("impersonation_id")).isEqualTo(session);
    }

    /** A header the caller controls must never be able to produce a 500. */
    @Test
    void aMalformedSessionIdIsAForbiddenEnvelopeNotAServerError() throws Exception {
        mockMvc.perform(get(MEMBERS, orgId).with(support(provisionedPerson())).header(HEADER, "not-a-uuid"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.errors[0].source.header").value(HEADER))
                .andExpect(jsonPath("$.meta.requestId").exists()); // the envelope, not a container page
    }

    /**
     * Request threads are pooled, so a SecurityContext left swapped would hand the next request
     * somebody else's identity. Over HTTP this is the observable half — the operator is themselves
     * again on the very next call; {@code shared.security.ImpersonationFilterTest} asserts the restore
     * itself, and is the test that fails if the {@code finally} block is deleted.
     */
    @Test
    void theRequestAfterAnImpersonatedOneSeesItsOwnIdentity() throws Exception {
        UUID operator = provisionedPerson();
        UUID session = openSession(support(operator), readOnly(member, orgId));

        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)).header(HEADER, session))
                .andExpect(status().isOk());

        mockMvc.perform(get(PLATFORM_USERS).with(support(operator)))
                .andExpect(status().isOk());
        mockMvc.perform(get(MEMBERS, orgId).with(support(operator)))
                .andExpect(status().isForbidden());
    }

    // --- fixtures -------------------------------------------------------------------------------

    private UUID openSession(JwtRequestPostProcessor caller, String body) throws Exception {
        ResultActions result = mockMvc.perform(post(SESSIONS).with(caller)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id"));
    }

    private static String readOnly(UUID targetPersonId, UUID orgId) {
        return "{\"targetPersonId\":\"" + targetPersonId + "\",\"orgId\":\"" + orgId
                + "\",\"reason\":\"ticket 4711 refund not showing\"}";
    }

    private static String write(UUID targetPersonId, UUID orgId) {
        return "{\"targetPersonId\":\"" + targetPersonId + "\",\"orgId\":\"" + orgId
                + "\",\"reason\":\"ticket 4711 fixing it for them\",\"mode\":\"WRITE\"}";
    }

    private static String createRole(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"Reviewer\",\"permissions\":[\"org:read\",\"member:read\"]}";
    }

    private static String roleCode() {
        return "REVIEWER_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private UUID provisionedPerson() {
        return ImpersonationFixtures.provisionedPerson(persons, identities, resolver.keycloakIssuer(),
                ProvisioningStatus.ACTIVE);
    }

    /**
     * One custom role carrying exactly these permissions, and the person in it. The organization row
     * itself is seeded by {@link EdgeSeed#organization} — it also mints the provider link the token's
     * {@code organization} claim resolves through, without which the tenant is unreachable.
     */
    private void seedOrgMembership(UUID orgId, UUID personId, Permission... permissions) {
        UUID roleId = UUID.randomUUID();
        jdbc.update("""
                insert into org_role (id, org_id, code, name, system_role, version, created_at)
                values (?, ?, 'MEMBER', 'Member', false, 0, now())
                """, roleId, orgId);
        for (Permission permission : permissions) {
            // @Enumerated(STRING) stores the enum NAME; the wire code (org:read) is a different string.
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)",
                    roleId, permission.name());
        }
        jdbc.update("""
                insert into membership (id, org_id, person_id, role_id, status, version, created_at)
                values (?, ?, ?, ?, 'ACTIVE', 0, now())
                """, UUID.randomUUID(), orgId, personId, roleId);
    }

    private boolean roleExists(UUID orgId, String code) {
        Integer count = jdbc.queryForObject("select count(*) from org_role where org_id = ? and code = ?",
                Integer.class, orgId, code);
        return count != null && count > 0;
    }

    private Map<String, Object> auditRow(String action, String target) {
        return jdbc.queryForMap("""
                select actor_person_id, on_behalf_of_person_id, impersonation_id
                from audit_log where action = ? and target = ?
                """, action, target);
    }
}
