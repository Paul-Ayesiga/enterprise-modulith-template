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
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * Phase 4: the async exchange pattern (discover handlers → poll → cancel under the handler's own
 * permission; workers are off in the test profile, so PENDING is a stable state), the rule that
 * SUBMITTING needs a person a key does not have, and the org-scoped ranked search indexed through
 * the public port.
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
    void anAgentDiscoversPollsAndCancelsUnderTheHandlersPermission() {
        UUID orgId = UUID.randomUUID();
        // The job was submitted by a PERSON (requester_person_id is NOT NULL, V24). A machine key is
        // never that person, so every artifact/cancel check falls through to the handler's own gate —
        // org-members exports demand member:read, which is what the key here holds.
        UUID requester = EdgeSeed.person(jdbc, "exchange-requester-" + UUID.randomUUID());
        UUID jobId = seedExport(orgId, requester, "org-members");
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "exporter",
                "exchange:read", "exchange:submit", "member:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            Map<String, Object> handlers = call(client, "exchange_handlers", Map.of());
            assertThat(list(handlers, "handlers")).anySatisfy(handler ->
                    assertThat(handler).containsEntry("id", "org-members"));

            Map<String, Object> polled = call(client, "exchange_job_get", Map.of("job_id", jobId.toString()));
            assertThat(polled).containsEntry("jobType", "EXPORT").containsEntry("status", "PENDING");

            Map<String, Object> listed = call(client, "exchange_jobs_list", Map.of());
            assertThat(list(listed, "items")).anySatisfy(row ->
                    assertThat(row).containsEntry("id", jobId.toString()));

            // No result yet — the URL tool must refuse, not mint a dead link.
            McpSchema.CallToolResult early = client.callTool(McpSchema.CallToolRequest.builder("exchange_result_url")
                    .arguments(Map.of("job_id", jobId.toString())).build());
            assertThat(early.isError()).isTrue();

            Map<String, Object> cancelled = call(client, "exchange_cancel",
                    Map.of("job_id", jobId.toString()));
            assertThat(cancelled).containsEntry("cancelRequested", true);
        }
    }

    /**
     * Submitting is closed to machines, and holding EVERY permission does not open it: the job
     * outlives the request and re-resolves its requester's permissions per record at processing
     * time, which a key's frozen minted subset cannot answer.
     */
    @Test
    void aMachineKeyIsRefusedAtSubmitEvenHoldingTheHandlersPermission() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "no-person",
                "exchange:read", "exchange:submit", "member:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("exchange_submit")
                    .arguments(Map.of("handler", "org-members", "format", "csv")).build());
            assertThat(result.isError()).isTrue();
            assertThat(McpTestSupport.textOf(result)).contains("FORBIDDEN")
                    .contains("needs a signed-in person");
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

    /**
     * A queued EXPORT exactly as {@code ExchangeJobStore.submit} leaves one — the submit path itself
     * is a REST/person surface now, so the row is the honest fixture for the poll/cancel tools.
     */
    private UUID seedExport(UUID orgId, UUID requesterPersonId, String handler) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into exchange_job (id, org_id, requester_person_id, job_type, handler,
                                          handler_version, format, status, created_at)
                values (?, ?, ?, 'EXPORT', ?, 1, 'CSV', 'PENDING', now())
                """, id, orgId, requesterPersonId, handler);
        return id;
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
