package ug.co.smsone.audit.internal;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * The audit query REST: the platform-admin view (all orgs), the org-scoped view gated by
 * {@code audit:read}, filtering, cursor pagination, and default-deny. Rows are seeded directly; the
 * event→row recording is covered by {@link AuditTrailTest}. {@code OrgAuthorization} is mocked so the
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
        jdbc.update("""
                insert into audit_log (id, org_id, action, target, detail, occurred_at, version, created_at)
                values (?, ?, ?, ?, ?, now(), 0, now())
                """, UUID.randomUUID(), orgId, action, target, "seed");
    }

    @Test
    void platformAdminListsAndFiltersByAction() throws Exception {
        String action = "test.seed-" + UUID.randomUUID();
        seed(null, action, "a");
        seed(null, action, "b");
        seed(null, "test.other-" + UUID.randomUUID(), "c");

        mockMvc.perform(get("/api/v1/audit").param("action", action)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].type").value("audit-entry"))
                .andExpect(jsonPath("$.data[0].attributes.action").value(action));
    }

    @Test
    void platformViewIsCursorPaginated() throws Exception {
        String action = "test.page-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            seed(null, action, "row-" + i);
        }

        mockMvc.perform(get("/api/v1/audit").param("action", action).param("page[size]", "2")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
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
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].source.parameter").value("from"));
    }

    @Test
    void orgScopedViewReturnsOnlyThatOrgWhenPermitted() throws Exception {
        UUID orgId = UUID.randomUUID();
        String subject = "owner-" + UUID.randomUUID();
        seed(orgId, "organization.member_added", "someone");
        seed(UUID.randomUUID(), "organization.member_added", "other-org"); // a different org
        given(orgAuthorization.hasPermission(subject, orgId, "audit:read")).willReturn(true);

        mockMvc.perform(get("/api/v1/orgs/{orgId}/audit", orgId).with(orgToken(subject, orgId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attributes.orgId").value(orgId.toString()));
    }

    @Test
    void orgScopedViewDeniesWithoutTheAuditPermission() throws Exception {
        UUID orgId = UUID.randomUUID();
        // No active-org scope on the token -> the evaluator denies before consulting OrgAuthorization.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/audit", orgId).with(jwt()))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor orgToken(String subject, UUID orgId) {
        return jwt().jwt(builder -> builder.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }
}
