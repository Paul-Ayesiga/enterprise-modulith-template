package ug.co.smsone.audit.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The gateway edge-audit seam: the gateway presents the shared secret and posts an edge decision, and
 * the platform records it against its audit trail — with the edge principal as the {@code actor}, which
 * is what makes this the one audit path where the actor arrives from another process. A wrong secret is
 * 401 and records nothing.
 */
@AutoConfigureMockMvc
class GatewayAuditTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void recordsAnEdgeAuditEventWithTheGatewaySecret() throws Exception {
        String subject = "key:gw-" + System.nanoTime();
        String body = "{\"action\":\"gateway.access_denied\",\"subject\":\"" + subject + "\","
                + "\"tenant\":null,\"method\":\"GET\",\"path\":\"/api/v1/reports\",\"status\":403,"
                + "\"reason\":\"forbidden_scope\",\"requestId\":\"gw-1\",\"traceId\":\"abc123\"}";

        mockMvc.perform(post("/internal/gateway/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Gateway-Secret", "dev-gateway-secret"))
                .andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
                "select count(*) from audit_log where action = ? and actor = ? and target = ?",
                Integer.class, "gateway.access_denied", subject, "GET /api/v1/reports");
        Assertions.assertThat(count).isEqualTo(1);

        String toState = jdbc.queryForObject(
                "select to_state from audit_log where actor = ?", String.class, subject);
        Assertions.assertThat(toState).contains("status=403").contains("reason=forbidden_scope");
    }

    @Test
    void wrongGatewaySecretIs401AndRecordsNothing() throws Exception {
        String subject = "key:reject-" + System.nanoTime();
        String body = "{\"action\":\"gateway.access_denied\",\"subject\":\"" + subject + "\","
                + "\"method\":\"GET\",\"path\":\"/x\",\"status\":401,\"reason\":\"unauthorized\"}";

        mockMvc.perform(post("/internal/gateway/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Gateway-Secret", "wrong-secret"))
                .andExpect(status().isUnauthorized());

        Integer count = jdbc.queryForObject(
                "select count(*) from audit_log where actor = ?", Integer.class, subject);
        Assertions.assertThat(count).isZero();
    }
}
