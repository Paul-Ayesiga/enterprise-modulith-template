package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/** The event vocabulary is finally on the wire, and it matches the enum — code for code. */
@AutoConfigureMockMvc
class WebhookEventTypesApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theCatalogListsEveryCodeTheSubscriptionEndpointsAccept() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/webhooks/event-types")
                        .with(jwt().jwt(t -> t.subject("anyone"))))
                .andExpect(status().isOk())
                .andReturn();
        List<String> codes = JsonPath.read(result.getResponse().getContentAsString(), "$.data[*].id");
        assertThat(codes).containsExactlyInAnyOrder(
                java.util.Arrays.stream(WebhookEventType.values())
                        .map(WebhookEventType::code).toArray(String[]::new));
        assertThat(codes).contains("org.member.added", "org.exchange.job_completed", "org.deleted");
    }
}
