package ug.co.smsone.subscription.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The gateway quota seam: the gateway presents the shared secret and a consumer (an org id), and the
 * platform answers with that org's edge quota straight from its subscription plan. An org with no
 * subscription resolves to the seeded FREE plan (60 requests/min); a wrong secret is 401; a non-org
 * consumer has no plan ceiling (limit -1).
 */
@AutoConfigureMockMvc
class GatewayQuotaTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsThePlansApiQuotaForAnOrg() throws Exception {
        UUID orgId = UUID.randomUUID(); // no subscription → the seeded FREE plan (api.requests.per_minute = 60)
        mockMvc.perform(get("/internal/gateway/quota").param("consumer", orgId.toString())
                        .header("X-Gateway-Secret", "dev-gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(60))
                .andExpect(jsonPath("$.windowSeconds").value(60));
    }

    @Test
    void wrongGatewaySecretIs401() throws Exception {
        mockMvc.perform(get("/internal/gateway/quota").param("consumer", UUID.randomUUID().toString())
                        .header("X-Gateway-Secret", "wrong-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aNonOrgConsumerHasNoCeiling() throws Exception {
        mockMvc.perform(get("/internal/gateway/quota").param("consumer", "not-a-uuid")
                        .header("X-Gateway-Secret", "dev-gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(-1));
    }
}
