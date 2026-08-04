package ug.co.smsone.signup.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        given(organizations.create(any(), any(), any(), any(), any())).willReturn(orgId);
        String email = "founder-" + UUID.randomUUID() + "@acme.test";

        mockMvc.perform(post("/api/v1/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"Acme Rockets!\",\"email\":\"" + email
                                + "\",\"firstName\":\"Ada\"}"))
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
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aGarbageTokenAnswersTheSameGeneric422() throws Exception {
        mockMvc.perform(get("/api/v1/signup/verify").param("token", "not-a-real-token"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"));
    }
}
