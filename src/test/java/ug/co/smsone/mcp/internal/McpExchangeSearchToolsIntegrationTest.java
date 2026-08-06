package ug.co.smsone.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.search.SearchDoc;
import ug.co.smsone.search.SearchIndex;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 4: the async exchange pattern (discover handlers → submit an export under the handler's own
 * permission → poll → cancel; workers are off in the test profile, so PENDING is a stable state) and
 * the org-scoped ranked search, indexed through the public port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpExchangeSearchToolsIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SearchIndex searchIndex;

    @Test
    void anExportSubmitsUnderTheHandlersPermissionThenPollsAndCancels() {
        UUID orgId = UUID.randomUUID();
        // org-members exports demand member:read (the handler's own gate, enforced at submit).
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "exporter",
                "exchange:read", "exchange:submit", "member:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            Map<String, Object> handlers = call(client, "exchange_handlers", Map.of());
            assertThat(list(handlers, "handlers")).anySatisfy(handler ->
                    assertThat(handler).containsEntry("id", "org-members"));

            Map<String, Object> job = call(client, "exchange_submit",
                    Map.of("handler", "org-members", "format", "csv"));
            assertThat(job).containsEntry("jobType", "EXPORT").containsEntry("status", "PENDING");
            String jobId = (String) job.get("id");

            Map<String, Object> polled = call(client, "exchange_job_get", Map.of("job_id", jobId));
            assertThat(polled).containsEntry("status", "PENDING");

            Map<String, Object> listed = call(client, "exchange_jobs_list", Map.of());
            assertThat(list(listed, "items")).anySatisfy(row ->
                    assertThat(row).containsEntry("id", jobId));

            // No result yet — the URL tool must refuse, not mint a dead link.
            McpSchema.CallToolResult early = client.callTool(McpSchema.CallToolRequest.builder("exchange_result_url")
                    .arguments(Map.of("job_id", jobId)).build());
            assertThat(early.isError()).isTrue();

            Map<String, Object> cancelled = call(client, "exchange_cancel", Map.of("job_id", jobId));
            assertThat(cancelled).containsEntry("cancelRequested", true);
        }
    }

    @Test
    void aKeyWithoutTheHandlersExportPermissionIsRefusedAtSubmit() {
        UUID orgId = UUID.randomUUID();
        // Holds the surface gate (exchange:submit) but not the HANDLER's own export permission.
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "no-export", "exchange:submit");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("exchange_submit")
                    .arguments(Map.of("handler", "org-members", "format", "csv")).build());
            assertThat(result.isError()).isTrue();
            assertThat(McpTestSupport.textOf(result)).contains("member:read");
            assertThat(jdbc.queryForObject("select count(*) from exchange_job where org_id = ?",
                    Integer.class, orgId)).isZero();
        }
    }

    @Test
    void searchFindsOnlyThisOrgsIndexedRecords() {
        UUID orgId = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        searchIndex.upsert(new SearchDoc(orgId, "document", "doc-1",
                "Quarterly revenue report", "Revenue grew 14 percent quarter over quarter."));
        searchIndex.upsert(new SearchDoc(otherOrg, "document", "doc-2",
                "Quarterly revenue report (other tenant)", "Not yours."));
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "searcher", "search:query");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            Map<String, Object> hits = call(client, "search_query", Map.of("q", "quarterly revenue"));
            List<Map<String, Object>> items = list(hits, "items");
            assertThat(items).hasSize(1); // the other tenant's record must not leak
            assertThat(items.get(0)).containsEntry("entityId", "doc-1");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(McpSyncClient client, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = client.callTool(
                McpSchema.CallToolRequest.builder(tool).arguments(args).build());
        assertThat(result.isError()).as("%s should succeed: %s", tool, McpTestSupport.textOf(result))
                .isNotEqualTo(Boolean.TRUE);
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> payload, String field) {
        return (List<Map<String, Object>>) payload.get(field);
    }
}
