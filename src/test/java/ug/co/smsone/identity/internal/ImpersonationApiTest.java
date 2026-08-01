package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.actor;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.admin;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.superadmin;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.support;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
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
import ug.co.smsone.shared.security.PlatformRole;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The operator surface — {@code /api/v1/admin/impersonations} — over HTTP: who may open a session,
 * against whom, for how long, in which mode, and who may end one. Every guardrail
 * {@link ImpersonationService} enforces has a test here that fails if the guardrail is deleted, and
 * each one also asserts the <em>negative</em>: a refused request must leave no session row behind,
 * because a session that exists is reach that exists.
 *
 * <p>Complemented by {@link ImpersonationReachTest} (what an open session actually reaches, and how
 * the audit trail attributes what it did), {@code shared.security.ImpersonationFilterTest} (header
 * handling and the SecurityContext restore) and {@link ImpersonationDisabledTest} (the kill switch).
 *
 * <p>{@code KeycloakUserAdminGateway} is mocked: it is the system edge, and the tier guard's one
 * remote call ({@code realmRoles}) is exactly what needs to be steerable here.
 */
@AutoConfigureMockMvc
class ImpersonationApiTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/admin/impersonations";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ImpersonationSessionRepository sessions;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private KeycloakUserAdminGateway keycloak;

    @Test
    void aSessionOpensReadOnlyForTheServerDefaultLifetime() throws Exception {
        String actor = actor();
        String target = provisionedUser(ProvisioningStatus.ACTIVE);

        UUID id = idOf(open(support(actor), body(target, "ticket 4711 refund missing"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("impersonation-session"))
                .andExpect(jsonPath("$.data.attributes.actorSubject").value(actor))
                .andExpect(jsonPath("$.data.attributes.targetSubject").value(target))
                .andExpect(jsonPath("$.data.attributes.mode").value("READ_ONLY")) // absent mode is the safe one
                .andExpect(jsonPath("$.data.attributes.active").value(true))
                .andExpect(jsonPath("$.data.attributes.endedAt").doesNotExist()));

        ImpersonationSession session = sessions.findById(id).orElseThrow();
        assertThat(lifetimeOf(session)).isEqualTo(Duration.ofMinutes(15)); // the shipped default
    }

    /**
     * The client's number is a request, never a grant: shorter is honoured, over-cap is <b>refused</b>
     * rather than clamped. Clamping is the tempting alternative and the wrong one — an operator who
     * asked for two hours and silently got thirty minutes meets it as an unexplained denial halfway
     * through an investigation.
     */
    @Test
    void theServerBoundsTheSessionLifetimeAndRefusesAnOverCapRequest() throws Exception {
        String actor = actor();
        String target = provisionedUser(ProvisioningStatus.ACTIVE);

        UUID shorter = idOf(open(support(actor),
                "{\"targetSubject\":\"" + target + "\",\"reason\":\"reading their invoice list\","
                        + "\"ttl\":\"PT5M\"}")
                .andExpect(status().isCreated()));
        assertThat(lifetimeOf(sessions.findById(shorter).orElseThrow())).isEqualTo(Duration.ofMinutes(5));

        String overCap = actor();
        open(support(overCap),
                "{\"targetSubject\":\"" + target + "\",\"reason\":\"reading their invoice list\","
                        + "\"ttl\":\"PT2H\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/ttl"));
        assertThat(sessionsOpenedBy(overCap)).isZero(); // refused, not quietly minted at the cap
    }

    /** The reason is the whole justification a later review sees; "help" is not one. */
    @Test
    void aReasonMustBeGivenAndMustSayEnoughToReview() throws Exception {
        String actor = actor();
        String target = provisionedUser(ProvisioningStatus.ACTIVE);

        open(support(actor), body(target, ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/reason"));

        open(support(actor), body(target, "  help  ")) // trimmed to 4 characters
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/reason"));

        assertThat(sessionsOpenedBy(actor)).isZero();
    }

    /**
     * Self-impersonation grants nothing — the swapped principal holds no platform role — but it writes
     * audit rows whose actor and {@code on_behalf_of} are the same person: a trail that reads as
     * oversight and records none. Refused even for a superadmin, who could otherwise mint it freely.
     */
    @Test
    void nobodyMayImpersonateThemselves() throws Exception {
        String actor = provisionedUser(ProvisioningStatus.ACTIVE);

        open(superadmin(actor), body(actor, "just looking at my own account"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/targetSubject"));

        assertThat(sessionsOpenedBy(actor)).isZero();
    }

    /**
     * {@code @SQLRestriction} makes a soft-deleted account and one that never existed the same absence
     * to every JPA query, and the two must not lead to the same answer: a 404 invites the operator to
     * fix what looks like a typo — or to re-provision the account somebody just erased — while a 409
     * says it exists and is out of reach on purpose.
     */
    @Test
    void aDeletedTargetIsAConflictAndAnUnknownOneIsNotFound() throws Exception {
        String actor = actor();
        String deleted = provisionedUser(ProvisioningStatus.ACTIVE);
        users.delete(users.findBySubject(deleted).orElseThrow());

        open(support(actor), body(deleted, "checking the deleted account"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));

        open(support(actor), body("kc-" + UUID.randomUUID(), "checking an unknown account"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));

        assertThat(sessionsOpenedBy(actor)).isZero();
    }

    /** An account with no access of its own must not become reachable through someone else's session. */
    @Test
    void aDisabledTargetCannotBeImpersonated() throws Exception {
        String actor = actor();
        String target = provisionedUser(ProvisioningStatus.DISABLED);

        open(support(actor), body(target, "investigating the disabled account"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));

        assertThat(sessionsOpenedBy(actor)).isZero();
    }

    /**
     * Wearing another operator's identity inherits their reach, so it is reserved to the top tier —
     * and the tier that authorizes it is checked against Keycloak's realm roles, not against anything
     * this application stores about the target.
     */
    @Test
    void onlyASuperadminMayImpersonateAnAccountHoldingAPlatformRole() throws Exception {
        String target = provisionedUser(ProvisioningStatus.ACTIVE);
        given(keycloak.realmRoles(target)).willReturn(Set.of(PlatformRole.SUPPORT));

        String supportActor = actor();
        open(support(supportActor), body(target, "peer support account looks stuck"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        String adminActor = actor();
        open(admin(adminActor), body(target, "peer support account looks stuck"))
                .andExpect(status().isForbidden());

        String superActor = actor();
        open(superadmin(superActor), body(target, "peer support account looks stuck"))
                .andExpect(status().isCreated());

        assertThat(sessionsOpenedBy(supportActor)).isZero();
        assertThat(sessionsOpenedBy(adminActor)).isZero();
        // Twice, not three times: a superadmin's answer cannot change the outcome, so the remote call
        // is skipped entirely rather than made and ignored.
        then(keycloak).should(times(2)).realmRoles(target);
    }

    /**
     * A write made inside someone's account is indistinguishable from their own to everyone but the
     * audit trail, so the mode is a tier above merely looking. The mode arrives in the body, which an
     * annotation cannot see — this is the test that the service checks it anyway.
     */
    @Test
    void aWriteCapableSessionRequiresPlatformAdmin() throws Exception {
        String target = provisionedUser(ProvisioningStatus.ACTIVE);

        String supportActor = actor();
        open(support(supportActor), writeBody(target, "fixing the stuck subscription for them"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));
        assertThat(sessionsOpenedBy(supportActor)).isZero();

        String adminActor = actor();
        open(admin(adminActor), writeBody(target, "fixing the stuck subscription for them"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.mode").value("WRITE"));
    }

    /**
     * Two live sessions for one (actor, target) pair would give the same reach under two different
     * stated reasons, and a review could not tell which reason covered which action. Re-issuing
     * therefore supersedes — and the supersede is itself audited, so the trail shows the first session
     * stopping rather than simply going quiet.
     */
    @Test
    void reIssuingAgainstTheSameTargetSupersedesTheOldSessionAndAuditsBoth() throws Exception {
        String actor = actor();
        String target = provisionedUser(ProvisioningStatus.ACTIVE);

        UUID first = idOf(open(support(actor), body(target, "ticket 4711 first look"))
                .andExpect(status().isCreated()));
        UUID second = idOf(open(support(actor), body(target, "ticket 4711 second look"))
                .andExpect(status().isCreated()));

        ImpersonationSession superseded = sessions.findById(first).orElseThrow();
        assertThat(superseded.getEndedAt()).isNotNull();
        assertThat(superseded.getEndedBy()).isEqualTo(actor);
        assertThat(superseded.isActive(Instant.now())).isFalse();
        assertThat(sessions.findById(second).orElseThrow().isActive(Instant.now())).isTrue();
        assertThat(sessionsOpenedBy(actor)).isEqualTo(2); // superseded, not overwritten — the row stays

        assertThat(auditRows("platform.impersonation_started", target)).hasSize(2);
        List<Map<String, Object>> supersedeRows = auditRows("platform.impersonation_superseded", target);
        assertThat(supersedeRows).hasSize(1);
        assertThat((String) supersedeRows.getFirst().get("to_state"))
                .contains(first.toString()).contains(second.toString());
        assertThat(supersedeRows.getFirst().get("actor")).isEqualTo(actor);
    }

    /**
     * The invariant is a read-then-insert, so it only holds if the pair is serialised. Two opens in
     * flight would otherwise both read an empty "previous" under READ COMMITTED, both insert, and leave
     * one operator holding two live sessions against one target under two different stated reasons —
     * the exact state a later review cannot untangle, reached without any error being raised.
     */
    @Test
    void twoOpensInFlightForOnePairStillLeaveExactlyOneLiveSession() throws Exception {
        String actor = actor();
        String target = provisionedUser(ProvisioningStatus.ACTIVE);
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> opens = List.of(
                    pool.submit(() -> openAfter(start, actor, target, "ticket 4711 first look")),
                    pool.submit(() -> openAfter(start, actor, target, "ticket 9002 second look")));
            for (Future<Integer> open : opens) {
                assertThat(open.get(30, TimeUnit.SECONDS)).isEqualTo(201);
            }
        }

        assertThat(sessionsOpenedBy(actor)).isEqualTo(2); // both rows exist — superseding is not deleting
        assertThat(liveSessionsAgainst(actor, target)).isEqualTo(1);
        assertThat(auditRows("platform.impersonation_superseded", target)).hasSize(1);
    }

    /**
     * A lapsed session is not superseded, because it was not live to supersede. Stamping {@code ended_at}
     * on it would put the ending strictly AFTER the {@code expires_at} where the reach actually stopped,
     * name an {@code ended_by} who did not end it, and write a {@code _superseded} row asserting a
     * re-issue cut short something that had already stopped. Expiry stays a read-time decision — nothing
     * sweeps, here or anywhere.
     */
    @Test
    void reIssuingAfterASessionLapsedLeavesTheLapsedRowUntouched() throws Exception {
        String actor = actor();
        String target = provisionedUser(ProvisioningStatus.ACTIVE);
        UUID lapsed = idOf(open(support(actor), body(target, "ticket 4711 first look"))
                .andExpect(status().isCreated()));
        jdbc.update("update impersonation_session set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), lapsed);

        open(support(actor), body(target, "ticket 4711 second look")).andExpect(status().isCreated());

        ImpersonationSession untouched = sessions.findById(lapsed).orElseThrow();
        assertThat(untouched.getEndedAt()).isNull();
        assertThat(untouched.getEndedBy()).isNull();
        assertThat(auditRows("platform.impersonation_superseded", target)).isEmpty();
    }

    /**
     * {@code orgId} is caller-supplied and deliberately unvalidated against the target's memberships, so
     * it must not choose which tenant's audit feed the row lands in: {@code GET /orgs/{id}/audit} filters
     * on {@code org_id} alone, and any {@code platform-support} operator could otherwise post 500
     * characters of chosen text into an unrelated org's trail about an unrelated user. Opening a session
     * is a platform act; what the session then does inside an org is what shows up there.
     */
    @Test
    void theLifecycleTrailIsPlatformScopedRatherThanFiledUnderTheRequestedOrg() throws Exception {
        String actor = actor();
        String target = provisionedUser(ProvisioningStatus.ACTIVE);
        UUID unrelatedOrg = UUID.randomUUID();

        open(support(actor), "{\"targetSubject\":\"" + target + "\",\"orgId\":\"" + unrelatedOrg
                + "\",\"reason\":\"ticket 4711 refund missing\"}").andExpect(status().isCreated());

        Map<String, Object> row = auditRows("platform.impersonation_started", target).getFirst();
        assertThat(row.get("org_id")).isNull();
        assertThat((String) row.get("to_state")).contains("org=" + unrelatedOrg); // recorded, not filed under
    }

    /**
     * A platform admin may end anyone's session, which is useless if they cannot find its id. The
     * listing therefore takes an {@code actor} — at the admin tier only, because who is being
     * investigated is itself sensitive and a peer at the same tier is not "someone above".
     */
    @Test
    void aPlatformAdminCanReviewAnotherOperatorsSessionsAndAPeerCannot() throws Exception {
        String operator = actor();
        UUID theirs = idOf(open(support(operator), body(provisionedUser(ProvisioningStatus.ACTIVE), "their ticket"))
                .andExpect(status().isCreated()));

        mockMvc.perform(get(PATH).param("actor", operator).with(support(actor())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        mockMvc.perform(get(PATH).param("actor", operator).with(admin(actor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + theirs + "')]").exists());
    }

    /**
     * Ending is open to the operator who holds the session and to any platform admin — the second case
     * is how an oversight gets cut short by someone other than the person being overseen. A third
     * operator at the same tier is not "someone above"; they are a peer, and to them the session does
     * not exist: a 403 here would confirm to anyone who found an id that it names a live session,
     * which is exactly the disclosure the repository's own doctrine (a leaked id is worthless to
     * anyone but its operator) exists to prevent.
     */
    @Test
    void onlyTheOperatorWhoOpenedASessionOrAPlatformAdminMayEndIt() throws Exception {
        String actor = actor();
        String target = provisionedUser(ProvisioningStatus.ACTIVE);
        UUID id = idOf(open(support(actor), body(target, "ticket 4711 refund missing"))
                .andExpect(status().isCreated()));

        mockMvc.perform(delete(PATH + "/{id}", id).with(support(actor())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
        assertThat(sessions.findById(id).orElseThrow().getEndedAt()).isNull();

        String platformAdmin = actor();
        mockMvc.perform(delete(PATH + "/{id}", id).with(admin(platformAdmin)))
                .andExpect(status().isNoContent());
        assertThat(sessions.findById(id).orElseThrow().getEndedBy()).isEqualTo(platformAdmin);

        // Ending twice keeps the FIRST ending: endedAt/endedBy answer "when did the reach stop and who
        // stopped it", and a second audit row would claim it stopped twice.
        mockMvc.perform(delete(PATH + "/{id}", id).with(support(actor)))
                .andExpect(status().isNoContent());
        assertThat(sessions.findById(id).orElseThrow().getEndedBy()).isEqualTo(platformAdmin);
        assertThat(auditRows("platform.impersonation_ended", target)).hasSize(1);
    }

    /** The listing is the operator's own record, not a window onto everyone else's oversight. */
    @Test
    void theListingShowsOnlyTheCallersOwnSessionsAndPaginatesByCursor() throws Exception {
        String actor = actor();
        UUID mine = idOf(open(support(actor), body(provisionedUser(ProvisioningStatus.ACTIVE), "first ticket"))
                .andExpect(status().isCreated()));
        UUID alsoMine = idOf(open(support(actor), body(provisionedUser(ProvisioningStatus.ACTIVE), "second ticket"))
                .andExpect(status().isCreated()));
        UUID theirs = idOf(open(support(actor()), body(provisionedUser(ProvisioningStatus.ACTIVE), "their ticket"))
                .andExpect(status().isCreated()));

        mockMvc.perform(get(PATH).with(support(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + mine + "')]").exists())
                .andExpect(jsonPath("$.data[?(@.id=='" + alsoMine + "')]").exists())
                .andExpect(jsonPath("$.data[?(@.id=='" + theirs + "')]").doesNotExist());

        mockMvc.perform(get(PATH).param("page[size]", "1").with(support(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.page.hasMore").value(true))
                .andExpect(jsonPath("$.links.next", Matchers.containsString("page[after]=")));
    }

    /** Opening a session is an operator capability: a plain authenticated user cannot reach it. */
    @Test
    void aTenantUserCannotOpenASession() throws Exception {
        String target = provisionedUser(ProvisioningStatus.ACTIVE);

        mockMvc.perform(post(PATH).with(jwt().jwt(token -> token.subject("tenant-" + UUID.randomUUID())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(target, "I would like to be them")))
                .andExpect(status().isForbidden());
    }

    // --- fixtures -------------------------------------------------------------------------------

    private ResultActions open(JwtRequestPostProcessor caller, String body) throws Exception {
        return mockMvc.perform(post(PATH).with(caller).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(String targetSubject, String reason) {
        return "{\"targetSubject\":\"" + targetSubject + "\",\"reason\":\"" + reason + "\"}";
    }

    private static String writeBody(String targetSubject, String reason) {
        return "{\"targetSubject\":\"" + targetSubject + "\",\"reason\":\"" + reason + "\",\"mode\":\"WRITE\"}";
    }

    private static UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id"));
    }

    private static Duration lifetimeOf(ImpersonationSession session) {
        return Duration.between(session.getStartedAt(), session.getExpiresAt());
    }

    private String provisionedUser(ProvisioningStatus status) {
        return ImpersonationFixtures.provisionedUser(users, status);
    }

    private int sessionsOpenedBy(String actorSubject) {
        Integer count = jdbc.queryForObject(
                "select count(*) from impersonation_session where actor_subject = ?", Integer.class, actorSubject);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> auditRows(String action, String target) {
        return jdbc.queryForList(
                "select actor, on_behalf_of, impersonation_id, org_id, from_state, to_state from audit_log "
                        + "where action = ? and target = ?", action, target);
    }

    private int liveSessionsAgainst(String actorSubject, String targetSubject) {
        Integer count = jdbc.queryForObject("""
                select count(*) from impersonation_session
                where actor_subject = ? and target_subject = ? and ended_at is null and expires_at > now()
                """, Integer.class, actorSubject, targetSubject);
        return count == null ? 0 : count;
    }

    /** Both threads released together, so the two opens overlap the way two clicks or a retry would. */
    private int openAfter(CyclicBarrier start, String actor, String target, String reason) throws Exception {
        start.await(30, TimeUnit.SECONDS);
        return open(support(actor), body(target, reason)).andReturn().getResponse().getStatus();
    }

}
