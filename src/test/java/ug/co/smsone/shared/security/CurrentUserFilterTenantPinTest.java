package ug.co.smsone.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.NoTenantAxis;

/**
 * Layer 1 of ADR 0010 §3.3, from both sides: a tenant-scoped route with no organization is refused in
 * the envelope with the request's own id, and the routes that legitimately name no organization are
 * not.
 *
 * <p>Driven directly wherever the assertion is about WHERE the refusal happens. Over MockMvc a 403 is
 * a 403 — {@code @PreAuthorize} would answer the same code on the same path, so an HTTP test cannot
 * tell this filter's refusal from method security's, and both would pass with the pin deleted. A chain
 * that reports what it saw makes three otherwise invisible things observable: that the chain was never
 * entered (so {@code OrgPolicyEnforcementFilter} at {@code @Order 3} and {@code SubscriptionAccessFilter}
 * at {@code @Order 5} never got to read a tenant table without a tenant), which state was on the thread
 * while it ran, and that nothing is left on the thread afterwards.
 *
 * <p>MockMvc is kept for the two claims that only exist on the wire: the envelope's shape, and that
 * {@code meta.requestId} is the request's real id rather than {@code ApiMetaFactory}'s {@code "unknown"}
 * placeholder — which is all a direct drive, with no {@code RequestIdFilter} above it, could ever prove.
 *
 * <p>What is NOT asserted here is the clearing, in any load-bearing sense.
 * {@code spring.threads.virtual.enabled} is on, so a request thread is never reused and the
 * {@code finally} cannot be observed to matter at this level. The clearing that can leak is on pooled
 * platform threads and is tested where it lives — {@code mcp.internal.McpTenantPinTest}.
 *
 * <p><strong>No harness axis, deliberately.</strong> A request arrives on a thread nobody pinned, and
 * this filter is what pins it — so the harness pinning first would reproduce the wrong starting state
 * and, worse, would substitute for the thing under test. {@code aPlatformOperatorWithNoOrganizationIs
 * NotRefusedOnATenantScopedPath} is the one that goes vacuous first: it asserts the chain sees PLATFORM,
 * and with a PLATFORM pin already on the thread it would keep passing with this filter's pin deleted
 * outright. Fixture writes therefore declare their own axis — see {@link #seededSubject()}.
 */
@AutoConfigureMockMvc
@NoTenantAxis("the filter's own pin is the subject; a harness pin would stand in for it and the "
        + "platform-operator case would pass with the pin deleted")
class CurrentUserFilterTenantPinTest extends AbstractIntegrationTest {

    /** Tenant-scoped, and deliberately a route whose own authorization would also refuse: see above. */
    private static final String TENANT_PATH = "/api/v1/orgs/" + UUID.randomUUID() + "/tickets";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CurrentUserFilter filter;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearThread() {
        SecurityContextHolder.clearContext();
        CurrentUserProvider.clear();
        TenantContext.clear();
    }

    @Test
    void aTenantScopedRouteWithNoOrganizationIsRefusedBeforeTheChain() throws Exception {
        AtomicReference<Tenant> seen = new AtomicReference<>();

        MockHttpServletResponse response = run(TENANT_PATH, orgLessPerson(), recording(seen));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"FORBIDDEN\"");
        assertThat(seen.get())
                .as("the refusal is at the edge — nothing downstream got to query a tenant table")
                .isNull();
        assertThat(TenantContext.current())
                .as("and the thread is handed back clean")
                .isEqualTo(Tenant.ABSENT);
    }

    /**
     * The wire half of the gate. The id is supplied inbound so the assertion is an equality against a
     * value this test chose, not "some string is present" — {@code ApiMetaFactory} answers
     * {@code "unknown"} when no {@code RequestIdFilter} ran above it, and that would satisfy a
     * {@code exists()} check while proving the opposite of what the gate asks for.
     */
    @Test
    void theRefusalIsTheEnvelopeAndCarriesTheRequestsOwnId() throws Exception {
        String requestId = "tenantpin-" + UUID.randomUUID().toString().replace("-", "");

        mockMvc.perform(get(TENANT_PATH)
                        .header("X-Request-Id", requestId)
                        .with(jwt().jwt(token -> token.subject(seededSubject()).claim("iss", EdgeSeed.ISSUER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.errors[0].status").value("403"))
                .andExpect(jsonPath("$.meta.requestId").value(requestId));
    }

    /**
     * The org-less caller who costs the most to get wrong. A token names a tenant only when it names
     * exactly ONE, so a person seated in two organizations resolves to no organization at all — and the
     * endpoint they use to choose one names no organization in its path either, because they have not
     * chosen yet. Refuse the org-less caller by default and a multi-org human is locked out of every
     * tenant, permanently, with no way back in.
     *
     * <p>{@code ProfileApiTest.aDualMemberSeesBothOrganizationsWithTheirRoleInEach} asserts what this
     * endpoint returns; this asserts only that it is reachable with no tenant, which is the property the
     * gate owns.
     */
    @Test
    void theOrgSwitcherIsReachableByAPersonWhoNamesNoOrganization() throws Exception {
        // Seeded on the platform axis and handed back unpinned — see seededSubject().
        EdgeSeed.MultiOrgPerson dual =
                TenantContext.callAsPlatform(() -> EdgeSeed.multiOrgPerson(jdbc, "ADMIN", "SUPPORT"));

        mockMvc.perform(get("/api/v1/me/organizations")
                        .with(jwt().jwt(token -> token.subject(dual.subject()).claim("iss", EdgeSeed.ISSUER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void aTokenNamingOneOrganizationPinsItForTheRestOfTheChain() throws Exception {
        String externalOrgId = "kc-" + UUID.randomUUID();
        String alias = "ext-" + UUID.randomUUID();
        // Seeded on the platform axis and handed back unpinned — see seededSubject().
        UUID orgId = TenantContext.callAsPlatform(() -> EdgeSeed.organization(jdbc, externalOrgId, alias));
        AtomicReference<Tenant> seen = new AtomicReference<>();

        MockHttpServletResponse response = run(TENANT_PATH,
                token(seededSubject(), Map.of(alias, Map.of("id", externalOrgId))), recording(seen));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(new Tenant.Org(orgId));
        assertThat(TenantContext.current()).isEqualTo(Tenant.ABSENT);
    }

    /**
     * The class of caller no prefix list can describe, and the reason {@link CurrentUserFilter} asks the
     * platform tier as well as the path. {@code POST} on {@code /api/v1/orgs/<id>/suspend} is
     * {@code hasRole('platform-admin')} — its own comment says a suspension cannot be gated on a
     * permission held inside the org being suspended — so the only caller who may reach it is one with
     * no organization, on a path that is tenant-scoped for everybody else. A rule reading only the path
     * would either 403 the operator or open the whole prefix to everyone.
     */
    @Test
    void aPlatformOperatorWithNoOrganizationIsNotRefusedOnATenantScopedPath() throws Exception {
        AtomicReference<Tenant> seen = new AtomicReference<>();

        MockHttpServletResponse response = run("POST", "/api/v1/orgs/" + UUID.randomUUID() + "/suspend",
                operator(), recording(seen));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get())
                .as("no tenant is named and none is missing — the operator IS the platform")
                .isEqualTo(Tenant.PLATFORM);
    }

    /**
     * The route half of the rule, from the side that costs a 403 rather than a hole: a path that names no
     * organization is not refused for having no tenant, whoever is calling. This is the claim an allowlist
     * of tenant-less prefixes could not keep — it made refusal the default for the whole URL space, so
     * every path nobody had thought to enumerate answered 403, including the ones that exist to answer
     * 404. Driven at the filter rather than over MockMvc because the point is that the CHAIN RUNS: on the
     * wire this path is a 404 either way, and a 404 would be reported for a request the gate had already
     * killed.
     */
    @Test
    void aRouteThatNamesNoOrganizationIsNotRefusedForHavingNoTenant() throws Exception {
        AtomicReference<Tenant> seen = new AtomicReference<>();

        MockHttpServletResponse response = run("/no/such/resource", orgLessPerson(), recording(seen));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get())
                .as("no tenant is named and none is missing — the chain runs on the platform axis")
                .isEqualTo(Tenant.PLATFORM);
    }

    /**
     * The case the {@code finally} exists for. A chain that returns normally is cleaned up by a trailing
     * statement too, so every test above passes either way; a chain that THROWS skips it.
     *
     * <p>The caller has to be one the gate ADMITS, and with a tenant of their own — an org-less caller on
     * {@link #TENANT_PATH} is refused before the chain is ever entered, so nothing throws and the test
     * proves the opposite of its name. Pinning a real tenant is also the state whose leak would matter:
     * PLATFORM left on a thread is a bug, one org's schema left on a pooled thread is a cross-tenant read.
     */
    @Test
    void aChainThatThrowsStillLeavesNoTenantOnTheThread() {
        String externalOrgId = "kc-" + UUID.randomUUID();
        String alias = "ext-" + UUID.randomUUID();
        // Seeded on the platform axis and handed back unpinned — see seededSubject().
        TenantContext.callAsPlatform(() -> EdgeSeed.organization(jdbc, externalOrgId, alias));
        Authentication member = token(seededSubject(), Map.of(alias, Map.of("id", externalOrgId)));

        assertThatThrownBy(() -> run(TENANT_PATH, member, (request, response) -> {
            throw new ServletException("downstream boom");
        })).isInstanceOf(ServletException.class);

        assertThat(TenantContext.current()).isEqualTo(Tenant.ABSENT);
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private MockHttpServletResponse run(String path, Authentication as, FilterChain chain) throws Exception {
        return run("GET", path, as, chain);
    }

    private MockHttpServletResponse run(String method, String path, Authentication as, FilterChain chain)
            throws Exception {
        SecurityContextHolder.getContext().setAuthentication(as);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    /** Reports the tenant the downstream chain would run with; left null when it never ran. */
    private static FilterChain recording(AtomicReference<Tenant> seen) {
        return (request, response) -> seen.set(TenantContext.current());
    }

    /** A real, resolvable human whose token names no organization — the ordinary org-less caller. */
    private Authentication orgLessPerson() {
        return token(seededSubject(), null);
    }

    /** A platform operator, no organization: the tier is on the token and nothing else is. */
    private Authentication operator() {
        return new JwtAuthenticationToken(jwtFor(seededSubject(), null),
                List.of(new SimpleGrantedAuthority("ROLE_" + PlatformRole.ADMIN)));
    }

    /**
     * The class runs unpinned (see the header), so a fixture write has to name its own axis or it
     * borrows a connection pointed at the empty {@code no_tenant} schema. {@code callAsPlatform} rather
     * than a bare {@code setPlatform()}: it restores absence in a {@code finally}, so the window the
     * filter is measured over stays unpinned even when seeding throws.
     */
    private String seededSubject() {
        String subject = "kc-" + UUID.randomUUID();
        return TenantContext.callAsPlatform(() -> {
            EdgeSeed.person(jdbc, subject);
            return subject;
        });
    }

    private static Authentication token(String subject, Map<String, Object> organizationClaim) {
        return new JwtAuthenticationToken(jwtFor(subject, organizationClaim), List.of());
    }

    /**
     * Named {@code jwtFor} and not {@code jwt}: a local method of that name would shadow the statically
     * imported {@code jwt()} post-processor the MockMvc cases above use, and the whole file would stop
     * compiling for a reason that reads like a Spring problem.
     *
     * <p>The {@code iss} claim is load-bearing, not decoration: {@code external_identity} and
     * {@code external_organization} are both keyed on it, so a token without it resolves to no person
     * and no tenant — which would make every assertion here pass for the wrong reason.
     */
    private static Jwt jwtFor(String subject, Map<String, Object> organizationClaim) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        if (organizationClaim != null) {
            builder.claim("organization", organizationClaim);
        }
        return builder.build();
    }
}
