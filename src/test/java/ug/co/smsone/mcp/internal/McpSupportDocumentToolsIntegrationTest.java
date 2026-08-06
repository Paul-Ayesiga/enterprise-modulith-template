package ug.co.smsone.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 3: the support desk (open → converse, with the caller's key subject as the recorded
 * opener/author) and org documents (metadata, presigned download, bytes-now/row-soft delete) over
 * the real loop. Storage is mocked at the PORT — the system edge, same as {@code DocumentApiTest};
 * the files module's own IT pins real S3 semantics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpSupportDocumentToolsIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private FileStorageProvider storage;

    @Test
    void aTicketOpensConversesAndAttributesTheKeySubject() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "desk", "ticket:read", "ticket:write");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            Map<String, Object> opened = call(client, "ticket_create", Map.of(
                    "subject", "Webhook deliveries failing since 02:00",
                    "category", "technical", "priority", "P2"));
            assertThat(opened).containsEntry("openerSubject", "key:" + key.keyId())
                    .containsEntry("status", "OPEN");
            String ticketId = (String) opened.get("id");

            call(client, "ticket_reply", Map.of("ticket_id", ticketId,
                    "body", "Receiver fixed; please confirm redelivery works."));

            Map<String, Object> conversation = call(client, "ticket_messages",
                    Map.of("ticket_id", ticketId));
            List<Map<String, Object>> messages = list(conversation, "messages");
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)).containsEntry("authorSubject", "key:" + key.keyId());

            Map<String, Object> listed = call(client, "tickets_list", Map.of());
            assertThat(list(listed, "items"))
                    .anySatisfy(ticket -> assertThat(ticket).containsEntry("id", ticketId));
        }
    }

    @Test
    void documentsReadPresignAndDeleteThroughTheStoragePort() {
        UUID orgId = UUID.randomUUID();
        UUID documentId = seedDocument(orgId, "q3-report.pdf", "docs/q3-report.pdf");
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "docs",
                "document:read", "document:manage");
        given(storage.exists("docs/q3-report.pdf")).willReturn(true);
        given(storage.presignGet(anyString(), any(Duration.class)))
                .willAnswer(inv -> URI.create("https://s3.example/" + inv.getArgument(0) + "?sig=x").toURL());

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            Map<String, Object> listed = call(client, "documents_list", Map.of());
            assertThat(list(listed, "items")).anySatisfy(document ->
                    assertThat(document).containsEntry("name", "q3-report.pdf"));

            Map<String, Object> url = call(client, "document_download_url",
                    Map.of("document_id", documentId.toString()));
            assertThat((String) url.get("url")).contains("docs/q3-report.pdf");

            call(client, "document_delete", Map.of("document_id", documentId.toString()));
            then(storage).should().delete("docs/q3-report.pdf"); // bytes now …
            Integer live = jdbc.queryForObject(
                    "select count(*) from document where id = ? and deleted_at is null",
                    Integer.class, documentId);
            assertThat(live).isZero(); // … row soft
        }
    }

    @Test
    void aReadOnlyDocumentKeyCannotDelete() {
        UUID orgId = UUID.randomUUID();
        UUID documentId = seedDocument(orgId, "keep.pdf", "docs/keep.pdf");
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "reader", "document:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("document_delete")
                    .arguments(Map.of("document_id", documentId.toString())).build());
            assertThat(result.isError()).isTrue();
            assertThat(McpTestSupport.textOf(result)).contains("document:manage");
            then(storage).should(org.mockito.Mockito.never()).delete(anyString());
        }
    }

    private UUID seedDocument(UUID orgId, String name, String storageKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into document (id, org_id, owner_subject, storage_key, name, content_type,
                                      size_bytes, source, version, created_at, created_by)
                values (?, ?, 'subject-owner', ?, ?, 'application/pdf', 1234, 'UPLOAD', 0, now(), 'test')
                """, id, orgId, storageKey, name);
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
