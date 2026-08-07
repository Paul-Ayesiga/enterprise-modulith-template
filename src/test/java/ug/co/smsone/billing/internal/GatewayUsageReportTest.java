package ug.co.smsone.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    /** A real tenant row — the ledger now refuses a consumer id that names no organization. */
    private UUID seedOrg() {
        UUID orgId = UUID.randomUUID();
        jdbc.update("""
                insert into organization (id, alias, name, status, version, created_at)
                values (?, ?, 'Usage Co', 'ACTIVE', 0, now())
                """, orgId, "usage-" + orgId.toString().substring(0, 8));
        return orgId;
    }

    @Test
    void secretGatedAdditiveUpsert() throws Exception {
        UUID orgId = seedOrg();
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

    /**
     * A well-formed UUID naming no organization must write nothing.
     *
     * <p>{@code api_usage_daily.org_id} is a soft ref with no cross-module FK — deliberately, so the
     * modulith can split later — so the database accepts any UUID happily and no later query ever finds
     * the row again. Nothing downstream fails; the usage simply becomes unattributable and Kill Bill
     * under-bills. The check that this asserts is the only thing standing in for the missing FK.
     */
    @Test
    void aConsumerNamingNoOrganizationIsNotBilled() throws Exception {
        UUID strangerId = UUID.randomUUID();

        mockMvc.perform(post("/internal/gateway/usage-report")
                        .header("X-Gateway-Secret", "dev-gateway-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counts\":{\"" + strangerId + "\":500}}"))
                .andExpect(status().isOk()); // accepted, but not billable

        assertThat(jdbc.queryForObject(
                "select count(*) from api_usage_daily where org_id = ?", Integer.class, strangerId))
                .isZero();
    }

    /**
     * The two internal gateway seams must agree about what a {@code consumer} string means.
     *
     * <p>They did not, and it was silent: the gateway's only source of a consumer id is API-key
     * introspection, which answers {@code organization.id}; the quota seam parsed exactly that, while
     * the usage seam resolved it through {@code external_organization} — a provider-minted key space an
     * {@code organization.id} is never in. So every consumer resolved to nothing, the ledger took no
     * rows, and metered billing silently became zero with no error anywhere.
     *
     * <p>Neither seam's own test could catch it, because each was internally consistent. Only driving
     * ONE string through BOTH can, which is what this does.
     */
    @Test
    void oneConsumerStringResolvesInBothGatewaySeams() throws Exception {
        UUID orgId = seedOrg();
        String consumer = orgId.toString(); // exactly what GatewayIntrospectionController answers

        mockMvc.perform(post("/internal/gateway/usage-report")
                        .header("X-Gateway-Secret", "dev-gateway-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counts\":{\"" + consumer + "\":7}}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "select requests from api_usage_daily where org_id = ?", Long.class, orgId))
                .as("the usage seam must attribute the consumer the gateway actually sends")
                .isEqualTo(7L);

        // The quota seam must accept the same string. This half is the weaker of the two on purpose:
        // an org with no subscription answers limit = -1 (unlimited), which is indistinguishable from
        // "resolved to nothing", so it cannot by itself detect a key-space flip. The usage assertion
        // above is the decisive one; this asserts only that one string is valid at both seams.
        mockMvc.perform(get("/internal/gateway/quota").param("consumer", consumer)
                        .header("X-Gateway-Secret", "dev-gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").exists());
    }
}
