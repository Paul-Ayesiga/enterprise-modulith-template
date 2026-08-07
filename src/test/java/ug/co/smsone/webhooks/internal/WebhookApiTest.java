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
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.shared.security.OrgAuthorization;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The webhook subscription REST surface: create returns the secret once, reads mask it, event/URL
 * validation, and org-scoped {@code webhook:manage} authorization ({@code OrgAuthorization} mocked).
 *
 * <p>The mock stubs {@link OrgAuthorization#permissions}, not {@code hasPermission}: the edge resolves a
 * caller's whole permission set once per request and {@code ApiPermissionEvaluator} reads it off
 * {@code CurrentUser}, so a {@code hasPermission} stub is never consulted on the request path at all.
 * The caller is a seeded person and the tenant a seeded provider link, because those are what the token
 * and its {@code organization} claim now resolve through.
 */
@AutoConfigureMockMvc
class WebhookApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OrgAuthorization orgAuthorization;

    /** A tenant the edge can resolve: an organization plus the provider link its claim names. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /** A person holding {@code webhook:manage} in {@code orgId}, and the token that resolves to them. */
    private RequestPostProcessor manager(UUID orgId) {
        String subject = "mgr-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        given(orgAuthorization.permissions(personId, orgId)).willReturn(Set.of("webhook:manage"));
        // The iss claim is load-bearing, not decoration: the edge resolves (iss, sub) through
        // external_identity to a person, and EdgeSeed linked this subject under EdgeSeed.ISSUER. A
        // token without it matches no link, resolves to no person, holds no org permission, and every
        // request 403s — which reads like a broken authorization rule rather than an unseeded token.
        return jwt().jwt(builder -> builder.subject(subject).claim("iss", EdgeSeed.ISSUER)
                .claim("organization", orgClaim(orgId)));
    }

    /** The alias-keyed {@code organization} claim Keycloak mints, rebuilt from the seeded link. */
    private Map<String, Object> orgClaim(UUID orgId) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return Map.of(String.valueOf(link.get("external_alias")),
                Map.of("id", String.valueOf(link.get("external_org_id"))));
    }

    @Test
    void createReturnsTheSecretOnceThenReadsMaskIt() throws Exception {
        UUID orgId = seedOrg();
        RequestPostProcessor manager = manager(orgId);

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
        UUID orgId = seedOrg();
        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgId).with(manager(orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://hooks.example.com/x\",\"events\":[\"org.does_not_exist\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/events"));
    }

    @Test
    void nonHttpUrlIsRejected() throws Exception {
        UUID orgId = seedOrg();
        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgId).with(manager(orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://internal/x\",\"events\":[\"org.member.added\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/url"));
    }

    @Test
    void deleteRemovesTheSubscription() throws Exception {
        UUID orgId = seedOrg();
        RequestPostProcessor manager = manager(orgId);
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

    /**
     * "What did we send that endpoint?" is the question asked AFTER someone deletes it, which is why
     * the delivery log outlives the subscription. Resolving the log through the restricted finder made
     * that retention unreachable for the log's entire life — 404 until the purge cascade destroyed it.
     * The unrestricted lookup is still tenant-scoped: another org's id is a 404 exactly as before.
     */
    @Test
    void theDeliveryLogOutlivesTheSubscriptionButStaysTenantScoped() throws Exception {
        UUID orgId = seedOrg();
        RequestPostProcessor manager = manager(orgId);
        String body = mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgId).with(manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://hooks.example.com/z\",\"events\":[\"org.status_changed\"]}"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.data.id");

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/webhooks/{id}", orgId, id).with(manager))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/webhooks/{id}/deliveries", orgId, id).with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        UUID otherOrg = seedOrg();
        mockMvc.perform(get("/api/v1/orgs/{orgId}/webhooks/{id}/deliveries", otherOrg, id)
                        .with(manager(otherOrg)))
                .andExpect(status().isNotFound());
    }

    /** The permission lookup itself: scoped to the right org, and still refused without {@code webhook:manage}. */
    @Test
    void withoutThePermissionAccessIsDenied() throws Exception {
        UUID orgId = seedOrg();
        // OrgAuthorization is stubbed only inside manager(), so this person resolves to nothing — which is
        // the branch the name promises, and the one the no-active-org case never reaches.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/webhooks", orgId)
                        .with(jwt().jwt(builder -> builder.subject("outsider-" + UUID.randomUUID())
                                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenWithNoActiveOrgIsDenied() throws Exception {
        UUID orgId = seedOrg();
        // No active-org scope on the token -> the evaluator denies before consulting OrgAuthorization.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/webhooks", orgId).with(jwt()))
                .andExpect(status().isForbidden());
    }

    /**
     * The redelivery surface (REST twin of the MCP {@code webhook_redeliver} tool): only a FAILED
     * row re-queues, exactly once — the second attempt hits the fenced UPDATE and reports the
     * conflict instead of double-applying. The permission gate is asserted with the row untouched.
     */
    @Test
    void aFailedDeliveryRedeliversOnceThenConflicts() throws Exception {
        UUID orgId = seedOrg();
        RequestPostProcessor manager = manager(orgId);
        String body = mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgId).with(manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://hooks.example.com/dead\",\"events\":[\"org.member.added\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID subscriptionId = UUID.fromString(JsonPath.read(body, "$.data.id"));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("""
                insert into webhook_delivery (id, subscription_id, org_id, event_type, payload, status,
                                              attempts, max_attempts, next_attempt_at, last_error, created_at)
                values (?, ?, ?, 'org.member.added', '{}', 'FAILED', 8, 8, now(), 'receiver answered 500', now())
                """, deliveryId, subscriptionId, orgId);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks/{id}/deliveries/{deliveryId}/redeliver",
                        orgId, subscriptionId, deliveryId).with(manager))
                .andExpect(status().isAccepted());
        Assertions.assertThat(jdbc.queryForObject(
                        "select status from webhook_delivery where id = ?", String.class, deliveryId))
                .isEqualTo("PENDING");
        Assertions.assertThat(jdbc.queryForObject(
                        "select attempts from webhook_delivery where id = ?", Integer.class, deliveryId))
                .isZero(); // a fresh retry cycle, not a resumed one

        // The fence: no longer FAILED, so a repeat is a 409 — never a silent double-requeue.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks/{id}/deliveries/{deliveryId}/redeliver",
                        orgId, subscriptionId, deliveryId).with(manager))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));
    }

    @Test
    void redeliverIsDeniedWithoutWebhookManageAndTheRowStaysFailed() throws Exception {
        UUID orgId = seedOrg();
        RequestPostProcessor manager = manager(orgId);
        String body = mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks", orgId).with(manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://hooks.example.com/locked\",\"events\":[\"org.member.added\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID subscriptionId = UUID.fromString(JsonPath.read(body, "$.data.id"));
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("""
                insert into webhook_delivery (id, subscription_id, org_id, event_type, payload, status,
                                              attempts, max_attempts, next_attempt_at, last_error, created_at)
                values (?, ?, ?, 'org.member.added', '{}', 'FAILED', 8, 8, now(), 'x', now())
                """, deliveryId, subscriptionId, orgId);

        // OrgAuthorization is stubbed only for manager()'s subject — this one resolves to nothing.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/webhooks/{id}/deliveries/{deliveryId}/redeliver",
                        orgId, subscriptionId, deliveryId)
                        .with(jwt().jwt(builder -> builder.subject("outsider-" + UUID.randomUUID())
                                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))))))
                .andExpect(status().isForbidden());
        Assertions.assertThat(jdbc.queryForObject(
                        "select status from webhook_delivery where id = ?", String.class, deliveryId))
                .isEqualTo("FAILED");
    }
}
