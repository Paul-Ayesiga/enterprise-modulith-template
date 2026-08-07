package ug.co.smsone.audit.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
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
 * the platform records it against its audit trail — the one audit path where the acting party arrives
 * from another process. A wrong secret is 401 and records nothing.
 *
 * <p>Where that party LANDS is what V13 changed. The gateway sends a string — a token subject, a key id,
 * a service name — and {@code audit_log.actor_person_id} now holds a {@code person.id}; none of those
 * three shapes is one, and this process has no issuer to resolve them against. So the typed column stays
 * NULL ({@code Attribution.NOBODY}, V13's spelling for a non-person actor) and
 * {@code AuditLogImpl.recordExternal} carries the principal as text on {@code to_state}, prefixed
 * {@code edgePrincipal=}. Both are asserted here: the null is the claim that nothing fabricated a human,
 * and the marker is the claim that nothing was lost.
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
                "select count(*) from audit_log where action = ? and to_state like ? and target = ?",
                Integer.class, "gateway.access_denied", recordedFor(subject), "GET /api/v1/reports");
        Assertions.assertThat(count).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "select actor_person_id, to_state from audit_log where to_state like ?", recordedFor(subject));
        // Nobody accountable: an edge principal is not a person of ours, and minting a uuid to fill the
        // column would put a fabricated human in the one table whose job is to say who acted.
        Assertions.assertThat(row.get("actor_person_id")).isNull();
        Assertions.assertThat(String.valueOf(row.get("to_state")))
                .contains("status=403").contains("reason=forbidden_scope");
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
                "select count(*) from audit_log where to_state like ?", Integer.class, recordedFor(subject));
        Assertions.assertThat(count).isZero();
    }

    /**
     * The {@code to_state} pattern that identifies this test's row. The subject is nanosecond-unique and
     * the prefix is {@code recordExternal}'s, so the match is exact in practice while staying a prefix —
     * the outcome text that follows it is the other half of the assertion, not part of the lookup.
     */
    private static String recordedFor(String subject) {
        return "edgePrincipal=" + subject + "%";
    }
}
