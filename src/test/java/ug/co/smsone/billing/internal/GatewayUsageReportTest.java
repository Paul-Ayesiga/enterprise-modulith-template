package ug.co.smsone.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The usage seam on real Postgres: the shared secret gates it, counts upsert additively per
 * (org, day), and non-org consumers are ignored rather than corrupting the ledger.
 */
@AutoConfigureMockMvc
class GatewayUsageReportTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void secretGatedAdditiveUpsert() throws Exception {
        UUID orgId = UUID.randomUUID();
        String body = "{\"counts\":{\"" + orgId + "\":120,\"not-an-org\":5}}";

        mockMvc.perform(post("/internal/gateway/usage-report")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()); // no secret, no ledger

        mockMvc.perform(post("/internal/gateway/usage-report")
                        .header("X-Gateway-Secret", "dev-gateway-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/gateway/usage-report")
                        .header("X-Gateway-Secret", "dev-gateway-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "select requests from api_usage_daily where org_id = ?", Long.class, orgId))
                .isEqualTo(240L); // two flushes add up
        assertThat(jdbc.queryForObject(
                "select count(*) from api_usage_daily where org_id is null", Integer.class)).isZero();
    }
}
