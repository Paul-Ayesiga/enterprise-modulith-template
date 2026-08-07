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
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 1 read/rename tools over the real loop: org profile, member and role listings (house cursor
 * pagination on the wire), subscription standing (FREE fallback), and the usage ledger aggregate.
 * Member WRITE tools are covered in {@code organization.internal.McpMemberWriteToolsTest}, which
 * can stub the Keycloak gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpAccountToolsIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

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

            // The dispatcher audits every mutation under the caller's key subject.
            Integer audited = jdbc.queryForObject("""
                    select count(*) from audit_log
                    where action = 'mcp.tool_invoked' and org_id = ? and target = 'org_update' and actor = ?
                    """, Integer.class, orgId, "key:" + key.keyId());
            assertThat(audited).isEqualTo(1);
        }
    }

    @Test
    void membersListPaginatesWithTheHouseCursors() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.seedOrg(jdbc, orgId, "page-" + orgId.toString().substring(0, 8), "Paged");
        UUID roleId = McpTestSupport.seedRole(jdbc, orgId, "MEMBER", "org:read");
        for (int i = 0; i < 3; i++) {
            McpTestSupport.seedMembership(jdbc, orgId, UUID.randomUUID(), roleId);
        }
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
        McpTestSupport.seedRole(jdbc, orgId, "AUDITOR", "org:read", "audit:read");
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
