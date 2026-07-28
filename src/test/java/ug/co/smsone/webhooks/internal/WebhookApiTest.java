package ug.co.smsone.webhooks.internal;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.shared.security.OrgAuthorization;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The webhook subscription REST surface: create returns the secret once, reads mask it, event/URL
 * validation, and org-scoped {@code webhook:manage} authorization ({@code OrgAuthorization} mocked).
 */
@AutoConfigureMockMvc
class WebhookApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrgAuthorization orgAuthorization;

    private RequestPostProcessor manager(UUID orgId, String subject) {
        given(orgAuthorization.hasPermission(subject, orgId, "webhook:manage")).willReturn(true);
        return jwt().jwt(builder -> builder.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }

    @Test
    void createReturnsTheSecretOnceThenReadsMaskIt() throws Exception {
        UUID orgId = UUID.randomUUID();
        RequestPostProcessor manager = manager(orgId, "mgr-" + UUID.randomUUID());

        String body = mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgId).with(manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://hooks.example.com/x\",\"events\":[\"org.member.added\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("webhook"))
                .andExpect(jsonPath("$.data.attributes.url").value("https://hooks.example.com/x"))
                .andExpect(jsonPath("$.data.attributes.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.attributes.secret", Matchers.startsWith("whsec_")))
                .andExpect(jsonPath("$.data.attributes.secret", Matchers.not(Matchers.containsString("…"))))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.data.id");

        // A subsequent read masks the secret — no random bytes revealed.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/webhooks/{id}", orgId, id).with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.secret").value("whsec_••••••"));

        // An empty delivery log for a fresh subscription.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/webhooks/{id}/deliveries", orgId, id).with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void unknownEventTypeIs422() throws Exception {
        UUID orgId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgId).with(manager(orgId, "mgr"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://hooks.example.com/x\",\"events\":[\"org.does_not_exist\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/events"));
    }

    @Test
    void nonHttpUrlIsRejected() throws Exception {
        UUID orgId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgId).with(manager(orgId, "mgr"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://internal/x\",\"events\":[\"org.member.added\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/url"));
    }

    @Test
    void deleteRemovesTheSubscription() throws Exception {
        UUID orgId = UUID.randomUUID();
        RequestPostProcessor manager = manager(orgId, "mgr");
        String body = mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgId).with(manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://hooks.example.com/y\",\"events\":[\"org.status_changed\"]}"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.data.id");

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/webhooks/{id}", orgId, id).with(manager))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/webhooks/{id}", orgId, id).with(manager))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutThePermissionAccessIsDenied() throws Exception {
        UUID orgId = UUID.randomUUID();
        // No active-org scope on the token -> the evaluator denies before consulting OrgAuthorization.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/webhooks", orgId).with(jwt()))
                .andExpect(status().isForbidden());
    }
}
