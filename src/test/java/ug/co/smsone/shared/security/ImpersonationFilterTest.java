package ug.co.smsone.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The filter's own contract, driven directly rather than over MockMvc — because the one thing that
 * has to be pinned here is invisible from the outside. A leaked {@code SecurityContext} on a pooled
 * request thread would hand the next request somebody else's identity, but inside a servlet chain
 * Spring Security's own {@code SecurityContextHolderFilter} clears the holder on the way out, so an
 * HTTP-level test passes with or without the {@code finally} block. Calling
 * {@link ImpersonationFilter#doFilter} with a chain that reports what it saw is what makes the swap
 * and the restore separately observable — delete the restore and
 * {@link #theContextIsSwappedForTheChainAndRestoredAfterwards()} fails; weaken the {@code finally} to a
 * trailing statement and {@link #aChainThatThrowsStillLeavesTheOperatorsOwnContextOnTheThread()} does.
 *
 * <p>Sessions are opened over HTTP by a platform superadmin: that tier short-circuits the target's
 * realm-role check, so this test needs no Keycloak stand-in of any kind. The behaviour reached
 * through the real filter chain is {@code identity.internal.ImpersonationReachTest}.
 */
@AutoConfigureMockMvc
class ImpersonationFilterTest extends AbstractIntegrationTest {

    private static final String HEADER = "X-Impersonate";
    private static final String PATH = "/api/v1/orgs";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ImpersonationFilter filter;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID operator;
    private UUID target;
    private Authentication operatorToken;

    @BeforeEach
    void seed() {
        // Both sides are real people now: the session row holds person ids, and the filter refuses a
        // caller with no person id before it looks at their platform tier.
        operator = provisionedPerson();
        target = provisionedPerson();
        operatorToken = tokenFor(operator);
        // The filter reads the actor from the context, exactly as it would after the security chain ran.
        SecurityContextHolder.getContext().setAuthentication(operatorToken);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theContextIsSwappedForTheChainAndRestoredAfterwards() throws Exception {
        UUID session = openSession("READ_ONLY");
        AtomicReference<Authentication> seen = new AtomicReference<>();

        MockHttpServletResponse response = run("GET", session, chain(seen));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isInstanceOf(ImpersonatedAuthenticationToken.class);
        assertThat(seen.get().getName()).isEqualTo(target.toString()); // the EFFECTIVE identity downstream
        assertThat(seen.get().getAuthorities())
                .as("no authority travels into a session — that emptiness is the mechanism").isEmpty();
        assertThat(((ImpersonatedPrincipal) seen.get().getPrincipal()).actorPersonId()).isEqualTo(operator);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the operator's own context must be back before the thread is reused")
                .isSameAs(operatorToken);
    }

    /**
     * The case the {@code finally} exists for, and the only one that can observe it. A chain that
     * returns normally is restored by a plain trailing statement too, so the test above passes either
     * way; a chain that THROWS skips that statement and leaves the target's identity on a pooled thread
     * for whoever gets it next.
     */
    @Test
    void aChainThatThrowsStillLeavesTheOperatorsOwnContextOnTheThread() throws Exception {
        UUID session = openSession("READ_ONLY");

        assertThatThrownBy(() -> run("GET", session, (request, response) -> {
            throw new ServletException("downstream boom");
        })).isInstanceOf(ServletException.class);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("a thrown request must not hand the next one the target's identity")
                .isSameAs(operatorToken);
    }

    @Test
    void anAbsentHeaderLeavesBothTheChainAndTheContextAlone() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        filter.doFilter(request, new MockHttpServletResponse(), chain(seen));

        assertThat(seen.get()).isSameAs(operatorToken);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(operatorToken);
    }

    /**
     * A malformed id, an unknown one, an ended one and an expired one are one answer, not four: the
     * caller controls this header, and a status code that told them which of the four they hit would
     * make a session id guessable one property at a time.
     */
    @Test
    void anUnresolvableSessionDeniesWithoutRunningTheChainOrTouchingTheContext() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();

        for (String header : List.of("not-a-uuid", UUID.randomUUID().toString())) {
            MockHttpServletResponse response = run("GET", header, chain(seen));

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("\"FORBIDDEN\"").contains(HEADER);
            assertThat(seen.get()).as("the request never reached anything").isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(operatorToken);
        }
    }

    /**
     * The safe-method choice, stated as a table. GET, HEAD and OPTIONS pass a read-only session — HEAD
     * is a GET without a body and OPTIONS is a capability probe, so neither can change state, and
     * refusing them would break ordinary clients for no gain. Everything else is refused before the
     * endpoint is reached, which is why a read-only session cannot write even where the target could.
     */
    @Test
    void aReadOnlySessionPassesTheSafeMethodsAndRefusesTheRest() throws Exception {
        UUID session = openSession("READ_ONLY");

        for (String method : List.of("GET", "HEAD", "OPTIONS")) {
            AtomicReference<Authentication> seen = new AtomicReference<>();
            MockHttpServletResponse response = run(method, session, chain(seen));
            assertThat(response.getStatus()).as(method).isEqualTo(200);
            assertThat(seen.get()).as(method).isInstanceOf(ImpersonatedAuthenticationToken.class);
        }

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            AtomicReference<Authentication> seen = new AtomicReference<>();
            MockHttpServletResponse response = run(method, session, chain(seen));
            assertThat(response.getStatus()).as(method).isEqualTo(403);
            assertThat(seen.get()).as(method).isNull();
        }
    }

    @Test
    void aWriteCapableSessionPassesAnUnsafeMethod() throws Exception {
        UUID session = openSession("WRITE");
        AtomicReference<Authentication> seen = new AtomicReference<>();

        MockHttpServletResponse response = run("POST", session, chain(seen));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isInstanceOf(ImpersonatedAuthenticationToken.class);
    }

    // --- fixtures -------------------------------------------------------------------------------

    private MockHttpServletResponse run(String method, Object header, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, PATH);
        request.addHeader(HEADER, String.valueOf(header));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    /** Records the authentication the downstream chain would run with, and returns 200. */
    private static FilterChain chain(AtomicReference<Authentication> seen) {
        return (request, response) -> seen.set(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Opened through the real endpoint so the row under test is one the service actually mints.
     * MockMvc's {@code jwt()} post-processor populates the holder, so the operator's own context is
     * (re)installed afterwards — never before.
     */
    private UUID openSession(String mode) throws Exception {
        String body = "{\"targetPersonId\":\"" + target + "\",\"reason\":\"ticket 4711 refund missing\","
                + "\"mode\":\"" + mode + "\"}";
        String response = mockMvc.perform(post("/api/v1/admin/impersonations")
                        .with(jwt().jwt(token -> token.subject(subjectOf(operator))
                                        .claim("iss", EdgeSeed.ISSUER))
                                .authorities(new SimpleGrantedAuthority("ROLE_" + PlatformRole.SUPERADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(operatorToken);
        return UUID.fromString(JsonPath.read(response, "$.data.id"));
    }

    /**
     * The {@code iss} is not decoration: {@code external_identity} is keyed on (issuer, subject), so a
     * token without it resolves to no person and the filter would refuse before doing anything the test
     * is about.
     */
    private static Authentication tokenFor(UUID personId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subjectOf(personId))
                .claim("iss", EdgeSeed.ISSUER)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + PlatformRole.SUPERADMIN)));
    }

    private static String subjectOf(UUID personId) {
        return "kc-" + personId;
    }

    /**
     * {@code person} and {@code external_identity} live in another module's internals, so this seeds
     * them as SQL rather than importing identity — the same trade this class already made, and the
     * reason it does not share {@code identity.internal.ImpersonationFixtures}.
     */
    private UUID provisionedPerson() {
        UUID personId = UUID.randomUUID();
        jdbc.update("""
                insert into person (id, status, provisioned_at, version, created_at)
                values (?, 'ACTIVE', now(), 0, now())
                """, personId);
        jdbc.update("""
                insert into external_identity (id, person_id, provider, issuer, external_subject,
                                               linked_at, version, created_at)
                values (?, ?, 'KEYCLOAK', ?, ?, now(), 0, now())
                """, UUID.randomUUID(), personId, EdgeSeed.ISSUER, subjectOf(personId));
        return personId;
    }
}
