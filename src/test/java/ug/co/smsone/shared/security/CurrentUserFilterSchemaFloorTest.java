package ug.co.smsone.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantSchemaFloor;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The version floor (ADR 0010 §4.4): a binary whose {@link TenantSchemaFloor#MIN_TENANT_SCHEMA_VERSION}
 * is above a tenant's recorded {@code platform.tenant_placement.schema_version} answers that tenant 503
 * with {@code Retry-After}, <strong>and serves every other tenant normally</strong>.
 *
 * <p>The second clause is the one worth writing a test for, so it is written the only way that can fail
 * on a global check: every case here drives at least two tenants through the same filter instance in the
 * same process, and {@link #aTenantBelowTheFloorIsRefusedWhileEveryOtherTenantIsServed} interleaves them
 * — served, refused, served, and then a tenant first seen AFTER the refusal. A gate that latched on the
 * first below-floor sighting, or that compared the floor against one fleet-wide number instead of the
 * caller's own row, passes the refusal and fails the two serves that bracket it.
 *
 * <p>Driven at the filter directly wherever the claim is about what the chain saw: on the wire a 503 is
 * a 503, and the interesting facts — that the chain was never entered, so nothing downstream got to
 * select from a schema missing a column, and that the served tenants were pinned to their OWN axis — are
 * invisible from outside. {@link #theRefusalIsTheEnvelopeWithRetryAfterAndTheRequestsOwnId} keeps MockMvc
 * for the two claims that only exist on the wire.
 *
 * <p><strong>No {@code @NoTenantAxis} here, deliberately</strong> — and the annotation's javadoc asks
 * that the decision be argued rather than assumed. The harness pin cannot make anything below
 * unfalsifiable, because the subject is the floor and not the pin: the served cases assert the chain saw
 * {@code Tenant.Org(id)}, which a harness PLATFORM pin does not supply, and the refused cases assert the
 * chain was never reached at all, which no pin can fake.
 */
@AutoConfigureMockMvc
class CurrentUserFilterSchemaFloorTest extends AbstractIntegrationTest {

    /** One below the floor: the smallest lag that must still be refused. */
    private static final int BEHIND = TenantSchemaFloor.MIN_TENANT_SCHEMA_VERSION - 1;

    /** Exactly the floor — "at least" means served, not "strictly above". */
    private static final int AT_FLOOR = TenantSchemaFloor.MIN_TENANT_SCHEMA_VERSION;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CurrentUserFilter filter;

    @Autowired
    private TenantSchemaFloor schemaFloor;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearThread() {
        SecurityContextHolder.clearContext();
        CurrentUserProvider.clear();
    }

    /**
     * The gate. A partial fleet degrades per tenant or the whole mechanism is a worse outage than the
     * one it prevents — so the refusal is bracketed by serves of a tenant that is fine, and followed by
     * a tenant that this process had never heard of when it decided to refuse.
     */
    @Test
    void aTenantBelowTheFloorIsRefusedWhileEveryOtherTenantIsServed() throws Exception {
        Seat behind = tenantAtSchemaVersion(BEHIND);
        Seat fine = tenantAtSchemaVersion(AT_FLOOR);

        assertServed(fine);
        assertRefused(behind);
        assertServed(fine);

        // Seeded only now: a gate that flipped a process-wide switch on the refusal above would refuse
        // this one too, and nothing about it was cached before the refusal happened.
        assertServed(tenantAtSchemaVersion(AT_FLOOR));
    }

    /**
     * The wire half. {@code Retry-After} is asserted to be exactly the interval the floor advertises,
     * because the two are one number on purpose: a client told to come back sooner than this process is
     * willing to re-read would meet the same cached refusal and conclude the platform is down.
     *
     * <p>The request id is supplied inbound so the assertion is an equality against a value this test
     * chose — {@code ApiMetaFactory} answers {@code "unknown"} when nothing set one, and that would
     * satisfy a mere existence check while proving the opposite.
     */
    @Test
    void theRefusalIsTheEnvelopeWithRetryAfterAndTheRequestsOwnId() throws Exception {
        Seat behind = tenantAtSchemaVersion(BEHIND);
        String requestId = "floor-" + UUID.randomUUID().toString().replace("-", "");

        mockMvc.perform(get("/api/v1/orgs/" + behind.orgId() + "/tickets")
                        .header("X-Request-Id", requestId)
                        .with(jwt().jwt(token -> token.subject(behind.subject())
                                .claim("iss", EdgeSeed.ISSUER)
                                .claim("organization", behind.organizationClaim()))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", String.valueOf(schemaFloor.retryAfterSeconds())))
                .andExpect(jsonPath("$.errors[0].code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.errors[0].status").value("503"))
                .andExpect(jsonPath("$.meta.requestId").value(requestId));
    }

    /**
     * The other direction of skew, and the reason the comparison is one-sided. Tenant migrations run to
     * completion BEFORE the rollout, so a pod routinely meets schemas newer than it expects; §4.6 makes
     * that safe (an unread extra column is nothing), and a binary rolled back under a migrated fleet has
     * to keep working or rollback stops being an option.
     */
    @Test
    void aTenantAheadOfTheFloorIsServed() throws Exception {
        assertServed(tenantAtSchemaVersion(AT_FLOOR + 7));
    }

    /**
     * Unknown is served, and it is a decision rather than an oversight. A tenant the registry has no row
     * for — one provisioned since the last fan-out, a registry not yet backfilled — cannot be proved
     * behind, and refusing on a fact we failed to establish turns one missing row into an outage for
     * everybody, which is precisely the shape this check exists to avoid. A tenant genuinely behind still
     * fails loudly at ADR §3.3 layers 2-3.
     */
    @Test
    void aTenantTheRegistryDoesNotKnowIsServed() throws Exception {
        Seat unplaced = tenant();

        assertServed(unplaced);
    }

    /**
     * The state the whole fleet is in the moment V57 lands, so it had better be served: the backfill
     * writes a placement row for every organization that already exists and deliberately leaves
     * {@code schema_version} NULL rather than guessing one — the honest value comes from
     * {@code tenant_pool}'s own Flyway history, which the runner's first pass supplies. V57's column
     * note names this exact reading as the requirement ("a floor check that read NULL as below the floor
     * would 503 the entire fleet"), and this is that sentence as a test.
     */
    @Test
    void aTenantWhoseVersionTheRegistryHasNotRecordedYetIsServed() throws Exception {
        Seat seat = tenant();
        placeAtSchemaVersion(seat.orgId(), null);

        assertServed(seat);
    }

    /**
     * The hot-path claim: the floor is a deploy-time fact, so it is read once per tenant and not once per
     * request. Asserted by changing the registry underneath a decision already made and watching the
     * answer NOT change — the only way to observe a read that did not happen.
     *
     * <p>Both halves are the documented cache contract, not incidental behaviour. The refusal is
     * remembered for {@code RECHECK_AFTER} (the test stays well inside it) and the {@code Retry-After}
     * this process hands out is that same interval, which is what stops the staleness from being
     * permanent. The serve is remembered for the life of the process, because the transition it records
     * is one-way: {@code schema_version} only increases and the floor is compiled in, so a tenant that
     * has met this binary's floor cannot stop meeting it — the row rewritten downwards here is a state
     * only a hand-run Flyway repair could produce, and a rollout is what re-reads it.
     */
    @Test
    void theFloorIsReadOncePerTenantAndNotOnEveryRequest() throws Exception {
        Seat behind = tenantAtSchemaVersion(BEHIND);
        Seat fine = tenantAtSchemaVersion(AT_FLOOR);
        assertRefused(behind);
        assertServed(fine);

        placeAtSchemaVersion(behind.orgId(), String.valueOf(AT_FLOOR));
        placeAtSchemaVersion(fine.orgId(), String.valueOf(BEHIND));

        assertRefused(behind);
        assertServed(fine);
    }

    // --- driving ------------------------------------------------------------------------------------

    /** Served: the chain runs, pinned to this tenant's own axis and nobody else's. */
    private void assertServed(Seat seat) throws Exception {
        AtomicReference<Tenant> seen = new AtomicReference<>();

        MockHttpServletResponse response = drive(seat, recording(seen));

        assertThat(response.getStatus())
                .describedAs("tenant %s is at or above the floor and must be served normally", seat.orgId())
                .isEqualTo(200);
        assertThat(seen.get())
                .describedAs("and served on its OWN axis, which is what makes the degradation per tenant")
                .isEqualTo(new Tenant.Org(seat.orgId()));
    }

    /** Refused: 503, Retry-After, and — the load-bearing half — nothing downstream ever ran. */
    private void assertRefused(Seat seat) throws Exception {
        AtomicReference<Tenant> seen = new AtomicReference<>();

        MockHttpServletResponse response = drive(seat, recording(seen));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After"))
                .isEqualTo(String.valueOf(schemaFloor.retryAfterSeconds()));
        assertThat(response.getContentAsString()).contains("\"SERVICE_UNAVAILABLE\"");
        assertThat(seen.get())
                .describedAs("the refusal is at the edge — no statement was issued against a schema this"
                        + " binary is ahead of")
                .isNull();
    }

    private MockHttpServletResponse drive(Seat seat, FilterChain chain) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(seat.token());
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/orgs/" + seat.orgId() + "/tickets");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    /** Reports the tenant the downstream chain would run with; left null when it never ran. */
    private static FilterChain recording(AtomicReference<Tenant> seen) {
        return (request, response) -> seen.set(TenantContext.current());
    }

    // --- fixtures -----------------------------------------------------------------------------------

    /** An organization, the token that names exactly it, and the person the token resolves to. */
    private record Seat(UUID orgId, String alias, String externalOrgId, String subject) {

        Map<String, Object> organizationClaim() {
            return Map.of(alias, Map.of("id", externalOrgId));
        }

        Authentication token() {
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                    .subject(subject)
                    // Load-bearing: external_identity and external_organization are both keyed on the
                    // issuer, so a token without it resolves to no person and no tenant — and every
                    // assertion here would then pass for the wrong reason.
                    .claim("iss", EdgeSeed.ISSUER)
                    .claim("organization", organizationClaim())
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                    .build();
            return new JwtAuthenticationToken(jwt, List.of());
        }
    }

    private Seat tenantAtSchemaVersion(int schemaVersion) {
        Seat seat = tenant();
        placeAtSchemaVersion(seat.orgId(), String.valueOf(schemaVersion));
        return seat;
    }

    /** A resolvable tenant with no placement row at all — the registry has never heard of it. */
    private Seat tenant() {
        String externalOrgId = "kc-" + UUID.randomUUID();
        String alias = "ext-" + UUID.randomUUID();
        UUID orgId = EdgeSeed.organization(jdbc, externalOrgId, alias);
        String subject = "kc-" + UUID.randomUUID();
        EdgeSeed.person(jdbc, subject);
        // No membership: the token names the org and CurrentUserProvider resolves the tenant from the
        // claim, so a seat inside it would add nothing the floor cares about.
        return new Seat(orgId, alias, externalOrgId, subject);
    }

    /**
     * Record where this tenant's schema has got to — V57's own columns, spelled as V57 spells them.
     * {@code schema_version} is {@code text} there because a Flyway version is dot-separated, so the
     * bound parameter is a string; {@code state} is constrained to
     * {@code PROVISIONING | ACTIVE | FAILED} and the fixture claims ACTIVE, because the point of every
     * case here is that the tenant is otherwise perfectly serviceable and only its VERSION decides.
     *
     * <p>The upsert rather than an insert: V57 backfills a row for every organization that existed when
     * it ran, and Phase 4's provisioner writes one for every organization created after it, so whether
     * this tenant already has a row is not this test's business to predict.
     *
     * @param schemaVersion the version to record, or {@code null} for the backfill's own state — hence
     *     the explicit {@code ::text}, which is what tells the driver the type of a null it is otherwise
     *     being handed with no column to infer it from
     */
    private void placeAtSchemaVersion(UUID orgId, String schemaVersion) {
        jdbc.update("""
                insert into platform.tenant_placement
                    (org_id, schema_name, datasource_name, state, schema_version, updated_at)
                values (?, ?, 'primary', 'ACTIVE', ?::text, now())
                on conflict (org_id) do update set schema_version = excluded.schema_version,
                                                   updated_at = now()
                """, orgId, TenantSchemas.TENANT_POOL, schemaVersion);
    }
}
