package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.admin;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.subjectOf;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.support;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.identity.internal.KeycloakUserAdminGateway.AccountPresence;
import ug.co.smsone.shared.security.PlatformAdmins;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * Suspending and restoring a person over HTTP — {@code POST /api/v1/admin/users/{personId}/disable} and
 * {@code .../enable}.
 *
 * <p><b>The gate is switched ON here</b> ({@code application-test.yaml} turns it off, because most API
 * tests authenticate with a {@code jwt()} mock that resolves to no person). That is not incidental
 * scaffolding: without it "suspended" would be a column nobody reads, and the first test below could not
 * be written at all.
 *
 * <p>Four failure modes are what this class exists for, one per hard part of the feature:
 * <ul>
 *   <li><b>Latency dressed as security.</b> {@link #aSuspendedPersonIsRefusedOnTheVeryNextRequest} warms
 *       the edge's resolution cache, suspends, and asserts the refusal on the next request <em>with the
 *       cached entry still present</em>. It fails the day anybody caches {@code person.status}.</li>
 *   <li><b>The nightly fight.</b> {@link #restoringSomebodyTheProviderNoLongerHoldsIsRefused} pins that an
 *       admin cannot re-enable an account {@code IdentityReconciliationJob} would disable again tonight.</li>
 *   <li><b>The silent acceptance.</b> {@link #somebodySuspendedWhileInvitedComesBackInvited} pins that a
 *       restore never marks an invitation as taken up by a person who never turned up.</li>
 *   <li><b>The foot-guns.</b> {@link #anOperatorCannotSuspendTheirOwnAccount} and
 *       {@link #theLastPlatformSuperAdminCannotBeSuspended}.</li>
 * </ul>
 *
 * <p>Keycloak is mocked for the same reason {@code IdentityReconciliationJobTest} mocks it: the behaviour
 * under test is how a restore reacts to each of the three presence answers, and two of them (deleted,
 * unreachable) are states a live Keycloak will not produce on demand. Postgres is real, as always.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.provisioning.gate-enabled=true")
class PersonLifecycleApiTest extends AbstractIntegrationTest {

    private static final String ADMIN = "/api/v1/admin/users";
    /** Any org-scoped read: the caller holds no membership, so reaching it AT ALL is the gate's answer. */
    private static final String ORG = "/api/v1/orgs/{orgId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PersonRepository persons;

    @Autowired
    private ExternalIdentityRepository identities;

    @Autowired
    private PersonResolver resolver;

    @Autowired
    private CacheManager caches;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private KeycloakUserAdminGateway keycloak;

    @MockitoBean
    private PlatformAdmins platformAdmins;

    @BeforeEach
    void providerHoldsEverybodyUnlessATestSaysOtherwise() {
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.PRESENT);
    }

    // --- 1. the suspension has to bite on the NEXT request ----------------------------------------

    /**
     * The security property the whole feature rests on, and the one a caching layer quietly breaks.
     *
     * <p>The first request is what makes this a real test rather than a tautology: it resolves
     * ({@code iss}, {@code sub}) → {@code person.id} and is refused by RBAC with {@code FORBIDDEN} —
     * meaning the person WAS resolved and the edge's cache is now warm. The suspension then lands, and
     * the very next request is refused with {@code ACCOUNT_DISABLED} instead. The two different codes
     * are the proof: one caller got past the gate, the other did not.
     *
     * <p>The last assertion is the one that pins the design. The cached resolution is STILL THERE after
     * the refusal — nothing was evicted, and nothing needed to be, because that entry answers "who is
     * this subject" and the gate re-reads "may they in" from {@code person.status} every time. Make
     * {@code PersonRepository.statusById} {@code @Cacheable} as a latency optimisation and this test goes
     * red, which is exactly what should happen: a suspended human would otherwise keep working until an
     * entry aged out.
     */
    @Test
    void aSuspendedPersonIsRefusedOnTheVeryNextRequest() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);
        UUID target = provisioned(ProvisioningStatus.ACTIVE);

        mockMvc.perform(get(ORG, UUID.randomUUID()).with(token(target)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN")); // past the gate
        assertThat(cachedResolutionOf(target)).as("the edge cache never warmed — the test proves nothing")
                .isNotNull();

        mockMvc.perform(post(ADMIN + "/" + target + "/disable").with(admin(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("user"))
                .andExpect(jsonPath("$.data.attributes.status").value("DISABLED"));

        mockMvc.perform(get(ORG, UUID.randomUUID()).with(token(target)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("ACCOUNT_DISABLED"));

        assertThat(cachedResolutionOf(target))
                .as("refused WITHOUT an eviction — the cache holds an identity, the gate reads the decision")
                .isNotNull();
        assertThat(disabledAtOf(target)).as("retention and erasure need the date, not an audit scan").isNotNull();
    }

    /** And the other direction: a restore is live on the next request too, not on the next deploy. */
    @Test
    void aRestoredPersonIsLetBackInOnTheVeryNextRequest() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);
        UUID target = provisioned(ProvisioningStatus.ACTIVE);

        suspend(operator, target).andExpect(status().isOk());
        mockMvc.perform(get(ORG, UUID.randomUUID()).with(token(target)))
                .andExpect(jsonPath("$.errors[0].code").value("ACCOUNT_DISABLED"));

        restore(operator, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("ACTIVE"));

        mockMvc.perform(get(ORG, UUID.randomUUID()).with(token(target)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN")); // past the gate again
        assertThat(disabledAtOf(target)).as("access has not stopped, so nothing should say when it did")
                .isNull();
    }

    // --- 2. an admin restore and the nightly sweep must not fight --------------------------------

    /**
     * The loop this refusal exists to prevent: {@code IdentityReconciliationJob} disables people whose
     * Keycloak account has disappeared, so restoring one of them here would be undone by tonight's pass,
     * and undone again tomorrow — the operator surface claiming one thing every evening and the system
     * doing another every morning. The provider is asked BEFORE access is granted, and a definite
     * "deleted" refuses.
     *
     * <p>Note what is not being protected: the person could not sign in either way, because Keycloak
     * authenticates and their account is gone. What is being protected is the record — and the operator,
     * who would otherwise be told the problem was fixed.
     */
    @Test
    void restoringSomebodyTheProviderNoLongerHoldsIsRefused() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);
        UUID target = provisioned(ProvisioningStatus.ACTIVE);
        suspend(operator, target).andExpect(status().isOk());

        given(keycloak.accountPresence(any())).willReturn(AccountPresence.ABSENT);

        restore(operator, target)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));
        assertThat(statusOf(target)).isEqualTo(ProvisioningStatus.DISABLED);

        // Fix it where it is actually broken, and the same call now works.
        given(keycloak.accountPresence(any())).willReturn(AccountPresence.PRESENT);
        restore(operator, target).andExpect(status().isOk());
        assertThat(statusOf(target)).isEqualTo(ProvisioningStatus.ACTIVE);
    }

    /**
     * A lookup that FAILED is not a deletion — the distinction {@code AccountPresence} exists for. The
     * job never acts on {@code UNKNOWN}, so no flip-flop can start from it, and refusing here would make
     * an operator's ability to undo their own mistake hostage to Keycloak's uptime.
     */
    @Test
    void anInconclusiveProviderLookupStillRestores() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);
        UUID target = provisioned(ProvisioningStatus.ACTIVE);
        suspend(operator, target).andExpect(status().isOk());

        given(keycloak.accountPresence(any())).willReturn(AccountPresence.UNKNOWN);

        restore(operator, target).andExpect(status().isOk());
        assertThat(statusOf(target)).isEqualTo(ProvisioningStatus.ACTIVE);
    }

    // --- 3. a restore must be explicit about what it restores ------------------------------------

    /**
     * <b>The one this feature is most likely to get wrong.</b> A person suspended at INVITED never turned
     * up; restoring them to ACTIVE would record an invitation as accepted by somebody who has not been
     * seen, destroying the "never signed in" signal and stamping an {@code activated_at} that is a lie.
     *
     * <p>They come back INVITED, and the final act is what makes that meaningful rather than pedantic:
     * their OWN first real request is what flips them ACTIVE, through the same gate path that has always
     * meant "the person finally showed up".
     */
    @Test
    void somebodySuspendedWhileInvitedComesBackInvited() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);
        UUID target = provisioned(ProvisioningStatus.INVITED);

        suspend(operator, target).andExpect(status().isOk());
        restore(operator, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("INVITED"));

        assertThat(statusOf(target)).isEqualTo(ProvisioningStatus.INVITED);
        assertThat(activatedAtOf(target)).as("a restore claimed a first login that never happened").isNull();

        mockMvc.perform(get(ORG, UUID.randomUUID()).with(token(target)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));

        assertThat(statusOf(target)).as("the gate, not the restore, is what activates a person")
                .isEqualTo(ProvisioningStatus.ACTIVE);
        assertThat(activatedAtOf(target)).isNotNull();
    }

    /** The other half of the same rule: somebody who HAD signed in gets their access back as it was. */
    @Test
    void somebodySuspendedWhileActiveComesBackActive() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);
        UUID target = provisioned(ProvisioningStatus.ACTIVE);

        suspend(operator, target).andExpect(status().isOk());
        restore(operator, target).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("ACTIVE"));

        assertThat(statusOf(target)).isEqualTo(ProvisioningStatus.ACTIVE);
    }

    // --- 4. the foot-guns ------------------------------------------------------------------------

    /**
     * Suspending yourself locks you out of the surface you would need to undo it. 409 rather than 403:
     * the caller IS allowed to suspend people — the request conflicts with the state of the target, that
     * state being "it is you".
     */
    @Test
    void anOperatorCannotSuspendTheirOwnAccount() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);

        mockMvc.perform(post(ADMIN + "/" + operator + "/disable").with(admin(operator)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));

        // The failure being guarded against is a refusal that wrote anyway — 409 on the way out, row
        // already changed on the way in.
        assertThat(statusOf(operator)).isEqualTo(ProvisioningStatus.ACTIVE);
        assertThat(auditRowsFor(operator)).isEmpty();
    }

    /**
     * The account whose loss cannot be repaired from inside the application: the tier that grants tiers.
     * A suspended {@code platform-admin} can be restored by any other admin; the last super-admin's
     * suspension waits for {@code PlatformAdminBootstrap} to re-seed one on the next restart.
     */
    @Test
    void theLastPlatformSuperAdminCannotBeSuspended() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);
        UUID target = provisioned(ProvisioningStatus.ACTIVE);
        given(platformAdmins.isSoleSuperAdmin(target)).willReturn(true);

        suspend(operator, target)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));

        assertThat(statusOf(target)).isEqualTo(ProvisioningStatus.ACTIVE);
    }

    // --- reach, audit, idempotency ---------------------------------------------------------------

    /**
     * The reach guard, on the floor this controller draws: reading a person is support's job, taking
     * their access away is not. Each refusal must leave the row alone — a 403 that still wrote is the
     * failure this asserts against.
     */
    @Test
    void onlyAPlatformAdminMayChangeAnotherPersonsAccess() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);
        UUID stranger = provisioned(ProvisioningStatus.ACTIVE);
        UUID target = provisioned(ProvisioningStatus.ACTIVE);

        // A plain signed-in person with no platform role at all.
        mockMvc.perform(post(ADMIN + "/" + target + "/disable").with(token(stranger)))
                .andExpect(status().isForbidden());
        assertThat(statusOf(target)).isEqualTo(ProvisioningStatus.ACTIVE);

        // platform-support reads this surface; it does not revoke access on it.
        mockMvc.perform(post(ADMIN + "/" + target + "/disable").with(support(operator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ADMIN + "/" + target + "/enable").with(support(operator)))
                .andExpect(status().isForbidden());
        assertThat(statusOf(target)).isEqualTo(ProvisioningStatus.ACTIVE);
    }

    /**
     * Both transitions are exactly what {@code audit_log} exists for, and both sides of the state have to
     * be there: "restored" alone cannot answer whether an invitation was marked accepted, which is the
     * one thing a restore can get wrong. The operator is accountable, never the person changed.
     */
    @Test
    void bothTransitionsAreAuditedWithTheirFromAndToStates() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);
        UUID target = provisioned(ProvisioningStatus.INVITED);

        suspend(operator, target).andExpect(status().isOk());
        restore(operator, target).andExpect(status().isOk());

        Map<String, Object> suspended = auditRow(target, "identity.person_disabled");
        assertThat(suspended.get("actor_person_id")).hasToString(operator.toString());
        assertThat(suspended.get("from_state")).hasToString("status=INVITED");
        assertThat(suspended.get("to_state")).hasToString("status=DISABLED");

        Map<String, Object> restored = auditRow(target, "identity.person_enabled");
        assertThat(restored.get("actor_person_id")).hasToString(operator.toString());
        assertThat(restored.get("from_state")).hasToString("status=DISABLED");
        // NOT "status=ACTIVE" — the trail has to show that an invitation stayed an invitation.
        assertThat(restored.get("to_state")).hasToString("status=INVITED");
    }

    /**
     * Re-sending an operation whose outcome already holds is the ordinary case (a double-click, a retry),
     * not an odd one, so it must not file a row claiming a change that did not happen — the same rule
     * {@code OrganizationService.suspend} and {@code PersonNameService.apply} obey.
     */
    @Test
    void repeatingEitherTransitionChangesNothingAndIsNotReAudited() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);
        UUID target = provisioned(ProvisioningStatus.ACTIVE);

        suspend(operator, target).andExpect(status().isOk());
        suspend(operator, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("DISABLED"));
        restore(operator, target).andExpect(status().isOk());
        restore(operator, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("ACTIVE"));

        assertThat(auditRowsFor(target))
                .containsExactlyInAnyOrder("identity.person_disabled", "identity.person_enabled");
    }

    /** An erased person and one who never existed are the same absence to an operator: 404, not 500. */
    @Test
    void anUnknownPersonIsNotFound() throws Exception {
        UUID operator = provisioned(ProvisioningStatus.ACTIVE);

        mockMvc.perform(post(ADMIN + "/" + UUID.randomUUID() + "/disable").with(admin(operator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(post(ADMIN + "/" + UUID.randomUUID() + "/enable").with(admin(operator)))
                .andExpect(status().isNotFound());
    }

    // --- fixtures --------------------------------------------------------------------------------

    private ResultActions suspend(UUID operator, UUID target) throws Exception {
        return mockMvc.perform(post(ADMIN + "/" + target + "/disable").with(admin(operator)));
    }

    private ResultActions restore(UUID operator, UUID target) throws Exception {
        return mockMvc.perform(post(ADMIN + "/" + target + "/enable").with(admin(operator)));
    }

    private UUID provisioned(ProvisioningStatus status) {
        return ImpersonationFixtures.provisionedPerson(persons, identities, resolver.keycloakIssuer(), status);
    }

    /** A plain signed-in person: a linked token subject and no platform authority whatsoever. */
    private static RequestPostProcessor token(UUID personId) {
        return jwt().jwt(t -> t.subject(subjectOf(personId)).claim("iss", EdgeSeed.ISSUER));
    }

    /**
     * The edge's cached {@code (provider, issuer, subject) → person.id} entry, read with the key
     * {@link PersonResolutionCache} builds in SpEL. Spelled out here rather than reached for through the
     * bean on purpose: the assertion is about what is IN the cache, and calling the cached method would
     * populate it.
     */
    private Cache.ValueWrapper cachedResolutionOf(UUID personId) {
        Cache cache = caches.getCache(PersonResolutionCache.CACHE);
        return cache == null ? null : cache.get(IdentityProvider.KEYCLOAK.name() + "|"
                + resolver.keycloakIssuer() + "|" + subjectOf(personId));
    }

    /** Straight from the column, so a projection cannot agree with a bug. */
    private ProvisioningStatus statusOf(UUID personId) {
        return ProvisioningStatus.valueOf(jdbc.queryForObject(
                "select status from person where id = ?", String.class, personId));
    }

    private Timestamp activatedAtOf(UUID personId) {
        return jdbc.queryForObject("select activated_at from person where id = ?", Timestamp.class, personId);
    }

    private Timestamp disabledAtOf(UUID personId) {
        return jdbc.queryForObject("select disabled_at from person where id = ?", Timestamp.class, personId);
    }

    private Map<String, Object> auditRow(UUID personId, String action) {
        return jdbc.queryForMap("select actor_person_id, from_state, to_state from audit_log "
                + "where action = ? and target = ?", action, personId.toString());
    }

    /** Lifecycle actions only — a name correction on the same person is a different trail. */
    private List<String> auditRowsFor(UUID personId) {
        return jdbc.queryForList("select action from audit_log where target = ? "
                + "and action in ('identity.person_disabled', 'identity.person_enabled')",
                String.class, personId.toString());
    }
}
