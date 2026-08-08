package ug.co.smsone.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantAxis;

/**
 * The window ADR 0010 §3.3 got wrong: the request paths that read the database BEFORE
 * {@code CurrentUserFilter} ({@code @Order -1}) can pin anything. Layer 2 assumed those borrows "touch
 * only explicitly-qualified platform tables" — but that qualification is a Phase 2 deliverable, so in
 * Phase 1 every one of them is unqualified, resolves the absent state to the empty {@code no_tenant}
 * schema, and fails. Four sites declare the platform axis for themselves instead, and this is the test
 * that holds them to it.
 *
 * <p><b>Every drive call runs inside {@link TenantAxis#withNoAxis}, and that is the design of this
 * class.</b> {@code AbstractIntegrationTest} pins PLATFORM on the test thread, which is honest for the
 * 89 classes whose subject runs inside a request — and fatal here, because it would supply the very
 * thing each of these tests is asserting the production code supplies. Under the harness pin all five
 * pass with all four production pins deleted. Taking the axis off for the one call under test is what
 * makes them fail. The fixtures around them stay on the harness pin, which is why this is
 * {@code withNoAxis} rather than {@code @NoTenantAxis} on the class.
 *
 * <p>Two properties are asserted at each site, and the second is the one that rots first:
 *
 * <ol>
 *   <li>the work SUCCEEDS with no axis on the thread — the pin is present;</li>
 *   <li>the axis the pin declared did NOT reach {@code chain.doFilter} — the pin is TIGHT. A pin
 *       widened to wrap the chain satisfies (1) forever while silently outliving the moment
 *       {@code CurrentUserFilter} sets the caller's real tenant, which is the fail-open §3.3 exists to
 *       forbid. Only (2) can see the difference.</li>
 * </ol>
 */
@AutoConfigureMockMvc
class PreAuthTenantPinTest extends AbstractIntegrationTest {

    /**
     * {@code TranslationBundles.BUNDLES_CACHE}, spelled out because that constant is package-private in
     * another module. Evicting it is not housekeeping: it is the only way to make the localizer inside
     * {@code EnvelopeErrorWriter} actually READ {@code translation}, and a warm bundle is exactly why
     * this class of bug survives a whole suite unnoticed and then appears once per L1 TTL in production.
     */
    private static final String TRANSLATION_BUNDLES = "translation-bundles";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeyAuthenticationFilter apiKeyFilter;

    @Autowired
    private ImpersonationFilter impersonationFilter;

    @Autowired
    private OrgMdcFilter orgMdcFilter;

    @Autowired
    private ApiAuthenticationEntryPoint entryPoint;

    @Autowired
    private CacheManager caches;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void leaveNothingBehind() {
        SecurityContextHolder.clearContext();
        CurrentUserProvider.clear();
    }

    /**
     * The one that takes every machine caller offline when it is wrong. {@code api_key} is read at
     * {@code @Order(-100)}, ninety-nine places ahead of the filter that learns the tenant — and it
     * cannot be otherwise, because the key is HOW the tenant is discovered (§2 table 1).
     */
    @Test
    void anApiKeyAuthenticatesWithNoTenantOnTheThread() throws Exception {
        String presentedKey = mintPlatformKey();
        AtomicReference<Tenant> insideChain = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/orgs");
        request.addHeader(ApiKeyAuthenticationFilter.HEADER, presentedKey);

        TenantAxis.withNoAxis(() -> {
            assertThat(TenantContext.current())
                    .as("the state the real chain is in at @Order(-100)")
                    .isEqualTo(Tenant.ABSENT);
            apiKeyFilter.doFilter(request, new MockHttpServletResponse(), recording(insideChain));
        });

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the key verified — the api_key probe found its row instead of an empty schema")
                .isInstanceOf(ApiKeyAuthenticationToken.class);
        assertThat(insideChain.get())
                .as("and the pin ended with the lookup: the chain — where CurrentUserFilter sets the "
                        + "REAL tenant — is entered with no axis, not with the platform one")
                .isEqualTo(Tenant.ABSENT);
    }

    /**
     * The same filter reached from a thread that already holds a tenant.
     * {@code GatewayIntrospectionController} calls the same authenticator from inside a request
     * {@code CurrentUserFilter} has already pinned, so the lookup must give the axis back;
     * {@code callAsPlatform} restores where a bare {@code set}/{@code clear} pair would take it away and
     * leave every read after this filter on the empty schema.
     */
    @Test
    void theKeyLookupGivesBackAnAxisItWasHandedRatherThanClearingIt() throws Exception {
        String presentedKey = mintPlatformKey();
        UUID callersOrg = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/orgs");
        request.addHeader(ApiKeyAuthenticationFilter.HEADER, presentedKey);

        Tenant previous = TenantContext.current();
        TenantContext.set(callersOrg);
        try {
            apiKeyFilter.doFilter(request, new MockHttpServletResponse(), (rq, rs) -> { });

            assertThat(TenantContext.current()).isEqualTo(Tenant.of(callersOrg));
        } finally {
            TenantContext.restore(previous);
        }
    }

    /**
     * {@code ImpersonationFilter} runs at {@code @Order(-2)} and reads twice before it delegates: the
     * operator through {@code CurrentUserProvider}, then {@code impersonation_session} (§2 table 20,
     * platform "hard constraint" for precisely this reason). This drives the first read and the refusal
     * it produces, with the translation bundle evicted so that the refusal's own envelope really does
     * reach {@code translation} on the way out.
     *
     * <p>403 is the load-bearing assertion. Unpinned, the refusal never renders at all: the localizer's
     * read fails on {@code relation "translation" does not exist} and a deliberate, explained 403 leaves
     * as a 500.
     */
    @Test
    void anImpersonationRefusalRendersItsEnvelopeWithNoTenantOnTheThread() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(tokenFor("unlinked-" + UUID.randomUUID()));
        evictTranslationBundles();
        AtomicReference<Tenant> insideChain = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orgs");
        request.addHeader("X-Impersonate", UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        TenantAxis.withNoAxis(() ->
                impersonationFilter.doFilter(request, response, recording(insideChain)));

        assertThat(response.getStatus())
                .as("refused, and the refusal RENDERED — not a 500 out of the error writer itself")
                .isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"FORBIDDEN\"");
        assertThat(insideChain.get()).as("a refusal never enters the chain").isNull();
    }

    /**
     * {@code OrgMdcFilter} shares {@code @Order(-1)} with {@code CurrentUserFilter} and the tie is broken
     * by bean-discovery order, which is filesystem order. Win it and this filter performs the caller
     * resolution — four platform tables — with nothing pinned, on every authenticated request. Its class
     * comment claims the tie is irrelevant; this is what keeps that true.
     */
    @Test
    void theOrgMdcFilterResolvesTheCallerWithNoTenantOnTheThread() throws Exception {
        String subject = "mdc-" + UUID.randomUUID();
        EdgeSeed.person(jdbc, subject);
        SecurityContextHolder.getContext().setAuthentication(tokenFor(subject));
        AtomicReference<Tenant> insideChain = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");

        TenantAxis.withNoAxis(() ->
                orgMdcFilter.doFilter(request, new MockHttpServletResponse(), recording(insideChain)));

        assertThat(insideChain.get())
                .as("the resolution happened and the chain was entered — and on no axis of this filter's "
                        + "choosing, so CurrentUserFilter still owns what the request runs as")
                .isEqualTo(Tenant.ABSENT);
    }

    /**
     * Every 401 the platform issues goes through here, from inside the security chain, before any tenant
     * exists. The same trap as the impersonation refusal with a far wider blast radius: an
     * unauthenticated request is the most common request there is.
     */
    @Test
    void theUnauthorizedEnvelopeRendersWithNoTenantOnTheThread() throws Exception {
        evictTranslationBundles();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orgs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        TenantAxis.withNoAxis(() ->
                entryPoint.commence(request, response, new BadCredentialsException("no token")));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"UNAUTHORIZED\"");
    }

    // --- fixtures -----------------------------------------------------------------------------------

    /** Reports the axis the downstream chain would run with; left null when the chain never ran. */
    private static FilterChain recording(AtomicReference<Tenant> seen) {
        return (request, response) -> seen.set(TenantContext.current());
    }

    /**
     * Minted over the real chain rather than inserted, so the credential is the one the platform would
     * actually issue — {@code ApiKeyHashing} is package-private in another module, and re-implementing
     * its format here would be a second definition of what a key IS.
     *
     * <p>A PLATFORM key deliberately: it names no organization at all, which is the case that proves the
     * {@code api_key} probe cannot be tenant-scoped even in principle.
     */
    private String mintPlatformKey() throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pin-probe-" + UUID.randomUUID() + "\"}")
                        .with(jwt().jwt(token -> token.subject("pin-admin-" + UUID.randomUUID()))
                                .authorities(new SimpleGrantedAuthority("ROLE_" + PlatformRole.ADMIN))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // The mint ran the whole filter chain on THIS thread, and CurrentUserFilter's finally clears
        // rather than restores — so the harness's platform pin is gone by the time this returns. Every
        // caller below either takes the axis off deliberately or sets its own, so nothing here depends
        // on which it is; anything that did would be depending on that clear, which is not a contract.
        SecurityContextHolder.clearContext();
        CurrentUserProvider.clear();
        return JsonPath.read(body, "$.data.attributes.secret");
    }

    private static JwtAuthenticationToken tokenFor(String subject) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    private void evictTranslationBundles() {
        Cache bundles = caches.getCache(TRANSLATION_BUNDLES);
        if (bundles != null) {
            bundles.clear();
        }
    }
}
