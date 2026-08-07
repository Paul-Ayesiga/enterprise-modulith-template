package ug.co.smsone.audit.internal;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.shared.security.OrgAuthorization;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The audit query REST: the platform-admin view (all orgs), the org-scoped view gated by
 * {@code audit:read}, filtering, cursor pagination, and default-deny. Rows are seeded directly; the
 * event→row recording is covered by {@link AuditRecordingTest}. {@code OrgAuthorization} is mocked so the
 * org-scoped authorization is exercised without standing up the full org RBAC fixture.
 */
@AutoConfigureMockMvc
class AuditApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OrgAuthorization orgAuthorization;

    private void seed(UUID orgId, String action, String target) {
        // actor_person_id, not actor: V13 made the acting party a person id, and NULL is its spelling
        // for a non-person actor — which a seed row written by no one is.
        jdbc.update("""
                insert into audit_log
                    (id, org_id, action, actor_person_id, target, from_state, to_state, occurred_at,
                     version, created_at)
                values (?, ?, ?, ?, ?, ?, ?, now(), 0, now())
                """, UUID.randomUUID(), orgId, action, UUID.randomUUID(), target, "before", "after");
    }

    @Test
    void platformAdminListsAndFiltersByAction() throws Exception {
        String action = "test.seed-" + UUID.randomUUID();
        seed(null, action, "a");
        seed(null, action, "b");
        seed(null, "test.other-" + UUID.randomUUID(), "c");

        mockMvc.perform(get("/api/v1/audit").param("action", action)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].type").value("audit-entry"))
                .andExpect(jsonPath("$.data[0].attributes.action").value(action))
                .andExpect(jsonPath("$.data[0].attributes.actor").value("admin-seed"))
                .andExpect(jsonPath("$.data[0].attributes.fromState").value("before"))
                .andExpect(jsonPath("$.data[0].attributes.toState").value("after"));
    }

    @Test
    void platformViewIsCursorPaginated() throws Exception {
        String action = "test.page-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            seed(null, action, "row-" + i);
        }

        mockMvc.perform(get("/api/v1/audit").param("action", action).param("page[size]", "2")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.page.hasMore").value(true))
                .andExpect(jsonPath("$.links.next", Matchers.containsString("page[after]=")));
    }

    @Test
    void platformViewIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/audit").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));
    }

    @Test
    void badInstantFilterIs422() throws Exception {
        mockMvc.perform(get("/api/v1/audit").param("from", "not-a-date")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("from"));
    }

    /**
     * {@code page[after]} is caller-controlled, so a cursor minted for another collection must be the
     * client's 422, not a 500: it decodes fine (the codec whitelists no property names) and only fails
     * deep inside the keyset query, which is exactly what {@code scrollPosition(SORT)} exists to catch.
     */
    @Test
    void aCursorFromAnotherCollectionIs422NotAServerError() throws Exception {
        String foreign = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("bogus=s:x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/audit").param("page[after]", foreign)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-support"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].source.parameter").value("page[after]"));
    }

    @Test
    void orgScopedViewReturnsOnlyThatOrgWhenPermitted() throws Exception {
        UUID orgId = seedOrg();
        String subject = "owner-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        seed(orgId, "organization.member_added", "someone");
        seed(UUID.randomUUID(), "organization.member_added", "other-org"); // a different org
        // The edge resolves the caller's whole permission set once, so THAT is what the mock answers —
        // a hasPermission stub is never consulted on the request path any more.
        given(orgAuthorization.permissions(personId, orgId)).willReturn(java.util.Set.of("audit:read"));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/audit", orgId).with(orgToken(subject, orgId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attributes.orgId").value(orgId.toString()));
    }

    /** The permission lookup itself: scoped to the right org, and still refused without {@code audit:read}. */
    @Test
    void orgScopedViewDeniesWithoutTheAuditPermission() throws Exception {
        UUID orgId = seedOrg();
        // OrgAuthorization is stubbed only for the permitted person above, so this one resolves to empty —
        // which is the branch the name promises, and the one the no-active-org case never reaches.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/audit", orgId)
                        .with(orgToken("outsider-" + UUID.randomUUID(), orgId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenWithNoActiveOrgIsDenied() throws Exception {
        UUID orgId = UUID.randomUUID();
        // No active-org scope on the token -> the evaluator denies before consulting OrgAuthorization.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/audit", orgId).with(jwt()))
                .andExpect(status().isForbidden());
    }

    /** A tenant the edge can resolve: an organization plus the provider link its claim names. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /** The alias-keyed {@code organization} claim Keycloak mints, rebuilt from the seeded link. */
    private RequestPostProcessor orgToken(String subject, UUID orgId) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return jwt().jwt(builder -> builder.subject(subject)
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }
}
