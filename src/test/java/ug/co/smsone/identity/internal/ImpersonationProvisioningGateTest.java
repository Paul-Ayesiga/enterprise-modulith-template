package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ug.co.smsone.identity.internal.ImpersonationFixtures.superadmin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * What {@code ProvisioningGateFilter} does to an impersonated request — the one context in the suite
 * where the gate is switched ON ({@code application-test.yaml} disables it, because every other API
 * test authenticates with a {@code jwt()} mock that resolves to no person).
 *
 * <p>The gate runs at {@code @Order(1)}, <em>inside</em> the swap {@code ImpersonationFilter} performs
 * at {@code @Order(-2)}, so it evaluates the target. That is deliberate — a session must not reach an
 * account the person themselves is refused — but it means the gate's lazy INVITED → ACTIVE activation
 * would fire on somebody else's read. {@link #aSessionNeverActivatesTheTargetItWears()} is the test
 * that fails if the impersonated branch ever goes back to the writing call.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.provisioning.gate-enabled=true")
class ImpersonationProvisioningGateTest extends AbstractIntegrationTest {

    private static final String SESSIONS = "/api/v1/admin/impersonations";
    private static final String HEADER = "X-Impersonate";
    /** Any org-scoped read: the caller holds no membership, so reaching it at all is the gate's answer. */
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
    private JdbcTemplate jdbc;

    /**
     * The organization every caller here names, and the one an impersonation session is opened against.
     * Seeded fresh per test, and nobody is a member of it.
     *
     * <p>It is what lets a request reach the gate at all. {@code CurrentUserFilter} runs at
     * {@code @Order(-1)}, ahead of {@code ProvisioningGateFilter} at {@code @Order(2)}, and refuses a
     * credential that names no organization on an org-scoped path (ADR 0010 §3.3 layer 1) — so without
     * it every request below is answered
     * {@code FORBIDDEN} by the tenancy edge before the gate ever runs, and the two session tests would
     * assert a target was left untouched by a filter that never looked at it. A brand-new Keycloak user
     * in an org, with no {@code person} row yet, is precisely the no-JIT case this class is about: their
     * token names the tenant, and nothing else about them exists.
     */
    private UUID callersOrganizationId;

    private Map<String, Object> callersOrganizationClaim;

    @BeforeEach
    void everyCallerNamesATenant() {
        String externalOrgId = "kc-" + UUID.randomUUID();
        String alias = "ext-" + UUID.randomUUID();
        callersOrganizationId = EdgeSeed.organization(jdbc, externalOrgId, alias);
        callersOrganizationClaim = Map.of(alias, Map.of("id", externalOrgId));
    }

    /** The control: without a provisioned row the gate refuses, so the gate really is live here. */
    @Test
    void anUnprovisionedSubjectIsRefusedByTheGateInThisContext() throws Exception {
        mockMvc.perform(get(ORG, UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject("ghost-" + UUID.randomUUID())
                                .claim("iss", EdgeSeed.ISSUER)
                                .claim("organization", callersOrganizationClaim))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("ACCOUNT_NOT_PROVISIONED"));
    }

    /** The other control: lazy activation still happens on the person's OWN first request. */
    @Test
    void aPersonsOwnFirstRequestStillActivatesThem() throws Exception {
        UUID personId = provisionedPerson(ProvisioningStatus.INVITED);

        mockMvc.perform(get(ORG, UUID.randomUUID()).with(token(personId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN")); // past the gate, refused by RBAC

        assertThat(persons.findById(personId).orElseThrow().getStatus())
                .isEqualTo(ProvisioningStatus.ACTIVE);
    }

    /**
     * Activation means "the account was finally used by the person it belongs to", and an operator
     * wearing them is not that. Letting the gate write here would flip {@code status}, stamp
     * {@code activated_at}, bump the version and publish {@code PersonActivated} off the back of a GET
     * inside a READ_ONLY session — attributed to the target, because the effective person is theirs,
     * and named nowhere in {@code audit_log}. The "never signed in" signal would be destroyed by the
     * very act of looking at it.
     */
    @Test
    void aSessionNeverActivatesTheTargetItWears() throws Exception {
        UUID operator = provisionedPerson(ProvisioningStatus.ACTIVE);
        UUID target = provisionedPerson(ProvisioningStatus.INVITED);
        UUID session = openReadOnlySession(operator, target);

        mockMvc.perform(get(ORG, UUID.randomUUID()).with(superadmin(operator)).header(HEADER, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN")); // the gate ALLOWED the target

        Person untouched = persons.findById(target).orElseThrow();
        assertThat(untouched.getStatus()).as("a read-only session performed a write")
                .isEqualTo(ProvisioningStatus.INVITED);
        assertThat(untouched.getActivatedAt()).as("activated_at would claim a first login that never happened")
                .isNull();
    }

    /** A DISABLED target is refused by the same gate, with the same code, as when they log in themselves. */
    @Test
    void aTargetDisabledMidSessionIsRefused() throws Exception {
        UUID operator = provisionedPerson(ProvisioningStatus.ACTIVE);
        UUID target = provisionedPerson(ProvisioningStatus.ACTIVE);
        UUID session = openReadOnlySession(operator, target);

        Person disabled = persons.findById(target).orElseThrow();
        disabled.disable(java.time.Instant.now());
        persons.save(disabled);

        mockMvc.perform(get(ORG, UUID.randomUUID()).with(superadmin(operator)).header(HEADER, session))
                .andExpect(status().isForbidden());
    }

    // --- fixtures -------------------------------------------------------------------------------

    /**
     * Superadmin so the target's realm-role check short-circuits and no Keycloak stand-in is needed.
     *
     * <p>The session is scoped to an organization, and that is load-bearing rather than decorative: the
     * swapped principal's {@code activeOrgId} IS the impersonated request's tenant, and an unscoped
     * session gives it none — so {@code CurrentUserFilter} would refuse the org-scoped read at
     * {@code @Order(-1)} with the same {@code FORBIDDEN} the assertions below expect from RBAC, and both
     * session tests would pass without the gate ever having seen the target.
     */
    private UUID openReadOnlySession(UUID operator, UUID target) throws Exception {
        String body = "{\"targetPersonId\":\"" + target + "\",\"orgId\":\"" + callersOrganizationId
                + "\",\"reason\":\"ticket 4711 refund missing\"}";
        String response = mockMvc.perform(post(SESSIONS).with(superadmin(operator))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.data.id"));
    }

    private UUID provisionedPerson(ProvisioningStatus status) {
        return ImpersonationFixtures.provisionedPerson(persons, identities, resolver.keycloakIssuer(), status);
    }

    /**
     * A plain signed-in person — one organization, no realm role, so only the gate has anything to say
     * about them. See {@link #callersOrganizationClaim} for why the organization is not optional.
     */
    private org.springframework.test.web.servlet.request.RequestPostProcessor token(UUID personId) {
        return jwt().jwt(t -> t.subject(ImpersonationFixtures.subjectOf(personId))
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", callersOrganizationClaim));
    }
}
