package ug.co.smsone.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 1 read/rename tools over the real loop: org profile, member and role listings (house cursor
 * pagination on the wire), subscription standing (FREE fallback), and the usage ledger aggregate.
 * Member WRITE tools are covered in {@code organization.internal.McpMemberWriteToolsTest}, which
 * can stub the Keycloak gateway.
 *
 * <p><b>The fixtures straddle the tenancy tiers, so each seed names its own</b> (ADR 0010 §2). The
 * harness pins PLATFORM, which is right for {@code organization}, {@code api_key} and
 * {@code api_usage_daily}; {@code org_role} and {@code membership} are the tenant's and are seeded inside
 * a {@code TenantContext.runAs(orgId, …)} span. The pin is declared HERE rather than pushed down into
 * {@code McpTestSupport} on purpose: {@code runAs} restores whatever the caller had, so it stays correct
 * whether or not that shared helper grows an axis of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpAccountToolsIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    // Name kept deliberately: docs/SRS.md FR-MCP-6 traces to this method by name. The name is not the
    // claim anyway — the assertions are, and they now hold the trail to naming WHICH credential renamed
    // the organization, not merely that the row exists.
    @Test
    void orgGetReadsAndOrgUpdateRenamesWithAnAuditTrail() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.seedOrg(jdbc, orgId, "acme-" + orgId.toString().substring(0, 8), "Acme Ltd");
        McpTestSupport.SeededKey key =
                McpTestSupport.seedOrgKey(jdbc, orgId, "acct", "org:read", "org:update");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            Map<String, Object> before = structured(client, "org_get", Map.of());
            assertThat(before).containsEntry("name", "Acme Ltd").containsEntry("status", "ACTIVE");

            Map<String, Object> renamed = structured(client, "org_update", Map.of("name", "Acme Group"));
            assertThat(renamed).containsEntry("name", "Acme Group");

            // The dispatcher audits every mutation, and the row has to survive BOTH halves of the
            // question a tenant asks it. queryForMap throws unless exactly one row matched, so the
            // lookup itself is the "audited exactly once" claim; the two assertions are the rest.
            // On the TENANT's axis: audit_log is split and routed on org_id (ADR 0010 §2), so a row
            // carrying an org is that tenant's compliance record and was written into the tenant home.
            // Read from the harness's platform pin this finds platform.audit_log — a real table with none
            // of these rows in it, so the failure would be an empty result rather than a missing relation.
            Map<String, Object> audited = TenantContext.callAs(orgId, () -> jdbc.queryForMap("""
                    select actor_person_id, to_state from audit_log
                    where action = 'mcp.tool_invoked' and org_id = ? and target = 'org_update'
                    """, orgId));
            // Attribution is a person.id now (V13's actor_person_id) and a machine key is answerable
            // to NOBODY, so it is NULL: a fabricated uuid here would be a synthetic human in the one
            // table whose job is to say who acted.
            assertThat(audited.get("actor_person_id")).isNull();
            // But NULL alone is not a trail — it says something renamed the org, not which credential
            // did, and every key in the tenant reads identically. The dispatcher takes the second,
            // person-less branch (recordExternal) so the key that acted is named in text beside the
            // outcome. Asserting the exact string, not a `contains`: this is the whole attribution.
            assertThat(String.valueOf(audited.get("to_state")))
                    .isEqualTo("edgePrincipal=key:" + key.keyId());
        }
    }

    @Test
    void membersListPaginatesWithTheHouseCursors() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.seedOrg(jdbc, orgId, "page-" + orgId.toString().substring(0, 8), "Paged");
        // org_role and membership are tenant-tier; the organization row above is the platform's.
        TenantContext.runAs(orgId, () -> {
            UUID roleId = McpTestSupport.seedRole(jdbc, orgId, "MEMBER", "org:read");
            for (int i = 0; i < 3; i++) {
                McpTestSupport.seedMembership(jdbc, orgId, UUID.randomUUID(), roleId);
            }
        });
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "members", "member:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            Map<String, Object> first = structured(client, "members_list", Map.of("page_size", 2));
            assertThat(items(first)).hasSize(2);
            Map<String, Object> page = page(first);
            assertThat(page).containsEntry("hasMore", true);
            String cursor = (String) page.get("nextCursor");
            assertThat(cursor).isNotBlank();

            Map<String, Object> second = structured(client, "members_list",
                    Map.of("page_size", 2, "page_after", cursor));
            assertThat(items(second)).hasSize(1);
            assertThat(page(second)).containsEntry("hasMore", false);
            assertThat(items(second).get(0)).containsEntry("roleCode", "MEMBER");
        }
    }

    @Test
    void rolesListShowsPermissionBundlesReadOnly() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.seedOrg(jdbc, orgId, "roles-" + orgId.toString().substring(0, 8), "Roles");
        // org_role is tenant-tier — the org's own grants, not platform reference data.
        TenantContext.runAs(orgId, () -> McpTestSupport.seedRole(jdbc, orgId, "AUDITOR", "org:read",
                "audit:read"));
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "roles", "role:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            Map<String, Object> result = structured(client, "roles_list", Map.of());
            List<Map<String, Object>> roles = items(result);
            assertThat(roles).anySatisfy(role -> {
                assertThat(role).containsEntry("code", "AUDITOR");
                assertThat(new java.util.HashSet<>((List<?>) role.get("permissions")))
                        .isEqualTo(java.util.Set.of("org:read", "audit:read"));
            });
        }
    }

    @Test
    void subscriptionGetFallsBackToTheSeededFreePlan() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "sub", "subscription:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            Map<String, Object> subscription = structured(client, "subscription_get", Map.of());
            assertThat(subscription).containsEntry("planCode", "FREE");
            assertThat(subscription.get("entitlements")).isInstanceOf(Map.class);
        }
    }

    @Test
    void usageSummaryAggregatesTheLedgerWindowOnly() {
        UUID orgId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        seedUsage(orgId, today, 30);
        seedUsage(orgId, today.minusDays(1), 20);
        seedUsage(orgId, today.minusDays(2), 10);
        seedUsage(orgId, today.minusDays(40), 100); // outside the window — must not count
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "usage", "usage:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            Map<String, Object> usage = structured(client, "usage_summary", Map.of("days", 30));
            assertThat(((Number) usage.get("totalRequests")).longValue()).isEqualTo(60L);
            assertThat((List<?>) usage.get("byDay")).hasSize(3);
        }
    }

    private void seedUsage(UUID orgId, LocalDate day, long requests) {
        jdbc.update("insert into api_usage_daily (org_id, day, requests) values (?, ?, ?)",
                orgId, day, requests);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSyncClient client, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = client.callTool(
                McpSchema.CallToolRequest.builder(tool).arguments(args).build());
        assertThat(result.isError()).as("%s should succeed: %s", tool, McpTestSupport.textOf(result))
                .isNotEqualTo(Boolean.TRUE);
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> windowed) {
        return (List<Map<String, Object>>) windowed.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> page(Map<String, Object> windowed) {
        return (Map<String, Object>) windowed.get("page");
    }
}
