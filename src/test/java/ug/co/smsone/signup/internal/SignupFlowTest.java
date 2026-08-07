package ug.co.smsone.signup.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.organization.Organizations;
import ug.co.smsone.organization.ProvisionedOrganization;
import ug.co.smsone.shared.idempotency.IdempotencyFilter;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The whole handshake against real Postgres: request queues a verification email (the delivery row
 * carries the link — the only place the token exists in plaintext), verify spends the token exactly
 * once and provisions through the {@link Organizations} port (mocked — Keycloak isn't in this
 * harness), a replay answers the same generic 422, and the disabled flag refuses both endpoints.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.signup.enabled=true")
class SignupFlowTest extends AbstractIntegrationTest {

    private static final Pattern TOKEN_IN_BODY = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Organizations organizations;

    @Test
    void requestVerifyProvisionAndSingleUse() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID ownerPersonId = UUID.randomUUID();
        given(organizations.create(any(), any(), any(), any(), any()))
                .willReturn(new ProvisionedOrganization(orgId, ownerPersonId));
        String email = "founder-" + UUID.randomUUID() + "@acme.test";

        mockMvc.perform(post("/api/v1/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"Acme Rockets!\",\"email\":\"" + email
                                + "\",\"givenName\":\"Ada\"}"))
                .andExpect(status().isAccepted());

        // The verification email is a durable delivery row; pull the link out of its body.
        String body = jdbc.queryForObject(
                "select body from notification_delivery where recipient = ? order by created_at desc limit 1",
                String.class, email);
        Matcher matcher = TOKEN_IN_BODY.matcher(body);
        assertThat(matcher.find()).as("verification email carries the token link").isTrue();
        String token = matcher.group(1);

        // The token is never stored in plaintext.
        assertThat(jdbc.queryForObject(
                "select count(*) from signup_request where token_hash = ?", Integer.class, token))
                .isZero();

        mockMvc.perform(get("/api/v1/signup/verify").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orgId.toString()));
        // The slug of "Acme Rockets!" drove the provisioning call.
        verify(organizations).create("acme-rockets", "Acme Rockets!", email, "Ada", null);
        assertThat(jdbc.queryForObject(
                "select status from signup_request where email = ?", String.class, email))
                .isEqualTo("COMPLETED");

        // Single-use: the same link now answers the generic 422.
        mockMvc.perform(get("/api/v1/signup/verify").param("token", token))
                .andExpect(status().isUnprocessableContent());
    }

    /**
     * The endpoint is unauthenticated by nature and used to dispatch a verification email on EVERY
     * call, so anyone could aim it at a stranger's address and send them a thousand messages. The
     * per-IP limiter does not help — the abuse is per-recipient. A pending request now suppresses
     * further mail to that address for the cooldown.
     *
     * <p>The response must NOT change, which is the subtle half: answering 409 to the second call
     * would tell an attacker which addresses already have a live request, turning the endpoint into
     * the enumeration oracle it is carefully written not to be. So both calls are 202 and the proof
     * lives in what did NOT happen — no second email, and the original token still valid.
     */
    @Test
    void aRepeatRequestIsAcceptedButSendsNoSecondEmail() throws Exception {
        String email = "repeat-" + UUID.randomUUID() + "@acme.test";
        String payload = "{\"organizationName\":\"Repeat Rockets\",\"email\":\"" + email + "\"}";

        mockMvc.perform(post("/api/v1/signup").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isAccepted());
        String firstTokenHash = jdbc.queryForObject(
                "select token_hash from signup_request where email = ?", String.class, email);

        // Five more attempts, as an abuser would.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/signup").contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isAccepted()); // same answer every time — no oracle
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from notification_delivery where recipient = ?", Integer.class, email))
                .as("six requests, one verification email")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from signup_request where email = ?", Integer.class, email))
                .as("no extra handshake rows")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select token_hash from signup_request where email = ?", String.class, email))
                .as("the original link keeps working — a resend must not invalidate it")
                .isEqualTo(firstTokenHash);
    }

    /**
     * Idempotency keys are scoped per principal, and an anonymous caller has none — so before
     * {@code IdempotencyFilter.shouldNotFilter} started requiring a subject, every unauthenticated
     * caller shared a single {@code "anonymous"} bucket. On this endpoint that was an enumeration
     * oracle: claim a key derived from someone's address (the obvious way a front-end de-duplicates a
     * form submit) with a different body, and 409 "already used with a different request payload"
     * versus 202 answers the exact question the constant 202 exists to refuse. It also allowed key
     * squatting — pre-claim the key a victim's client will derive and their real signup gets a 409.
     *
     * <p>The header must now be inert here: same key, different payload, still 202.
     */
    @Test
    void anAnonymousIdempotencyKeyCannotCollideWithAnotherCallers() throws Exception {
        String sharedKey = "shared-" + UUID.randomUUID().toString().replace("-", "");
        String first = "{\"organizationName\":\"Alpha Co\",\"email\":\"alpha-"
                + UUID.randomUUID() + "@acme.test\"}";
        String second = "{\"organizationName\":\"Beta Co\",\"email\":\"beta-"
                + UUID.randomUUID() + "@acme.test\"}";

        mockMvc.perform(post("/api/v1/signup").contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.KEY_HEADER, sharedKey).content(first))
                .andExpect(status().isAccepted());

        // A different anonymous caller, same key, different body — 409 here would be the oracle.
        mockMvc.perform(post("/api/v1/signup").contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.KEY_HEADER, sharedKey).content(second))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));
    }

    @Test
    void aGarbageTokenAnswersTheSameGeneric422() throws Exception {
        mockMvc.perform(get("/api/v1/signup/verify").param("token", "not-a-real-token"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"));
    }
}
