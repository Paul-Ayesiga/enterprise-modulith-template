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
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * Phase 3: the support desk (a key reads it but cannot write to it — the opener/author is a
 * {@code person.id} and a key is nobody) and org documents (metadata, presigned download,
 * bytes-now/row-soft delete) over the real loop. Storage is mocked at the PORT — the system edge,
 * same as {@code DocumentApiTest}; the files module's own IT pins real S3 semantics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpSupportDocumentToolsIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private FileStorageProvider storage;

    /**
     * The desk is READ-open and WRITE-closed to an agent holding only a key. {@code opener_person_id}
     * and {@code author_person_id} are NOT NULL (V36) because a ticket exists to be answered, so a
     * caller who is nobody is refused by name — and the refusal has to leave no row behind, which is
     * what the counts here are for. The reads still work and hand back the PERSON on the ticket.
     */
    @Test
    void aKeyReadsTheDeskButCannotOpenOrReplyBecauseItIsNobody() {
        UUID orgId = UUID.randomUUID();
        UUID opener = EdgeSeed.person(jdbc, "desk-opener-" + UUID.randomUUID());
        UUID ticketId = seedTicket(orgId, opener, "Webhook deliveries failing since 02:00",
                "Receiver fixed; please confirm redelivery works.");
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "desk", "ticket:read", "ticket:write");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            client.initialize();
            // Holding ticket:write is not enough: the tool needs a person and a key has none.
            McpSchema.CallToolResult opened = client.callTool(McpSchema.CallToolRequest.builder("ticket_create")
                    .arguments(Map.of("subject", "Deliveries failing again",
                            "category", "technical", "priority", "P2"))
                    .build());
            assertThat(opened.isError()).isTrue();
            assertThat(McpTestSupport.textOf(opened)).contains("FORBIDDEN")
                    .contains("an API key acts for nobody");
            // On the ORG's axis, for the reason seedTicket writes there: these are tenant-tier tables
            // and the harness's PLATFORM pin cannot see the rows the tools were reading.
            assertThat(TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                    "select count(*) from ticket where org_id = ?", Integer.class, orgId)))
                    .isEqualTo(1); // only the seeded one — nothing was opened

            McpSchema.CallToolResult replied = client.callTool(McpSchema.CallToolRequest.builder("ticket_reply")
                    .arguments(Map.of("ticket_id", ticketId.toString(),
                            "body", "Any update on this?"))
                    .build());
            assertThat(replied.isError()).isTrue();
            assertThat(McpTestSupport.textOf(replied)).contains("FORBIDDEN")
                    .contains("an API key acts for nobody");
            assertThat(TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                    "select count(*) from ticket_message where ticket_id = ?", Integer.class, ticketId)))
                    .isEqualTo(1); // the seeded message, nothing appended

            // Reading the desk is untouched, and attribution is the person's — never the credential's.
            Map<String, Object> ticket = call(client, "ticket_get",
                    Map.of("ticket_id", ticketId.toString()));
            assertThat(ticket).containsEntry("openerPersonId", opener.toString())
                    .containsEntry("status", "OPEN");

            Map<String, Object> conversation = call(client, "ticket_messages",
                    Map.of("ticket_id", ticketId.toString()));
            List<Map<String, Object>> messages = list(conversation, "messages");
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)).containsEntry("authorPersonId", opener.toString());

            Map<String, Object> listed = call(client, "tickets_list", Map.of());
            assertThat(list(listed, "items"))
                    .anySatisfy(row -> assertThat(row).containsEntry("id", ticketId.toString()));
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
            // On the ORG's axis: this document carries a non-null org_id, so the soft-deleted row is in
            // the tenant copy of the split table, and the same bare name on the harness's platform pin
            // would read the PERSONAL copy and report zero for the wrong reason.
            Integer live = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                    "select count(*) from document where id = ? and deleted_at is null",
                    Integer.class, documentId));
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

    /**
     * {@code owner_person_id} is a real {@code person.id} now — the column no longer holds a token
     * subject — so the fixture seeds the person it names rather than inventing a uuid nothing backs.
     * {@code created_by} is left NULL: null means a non-person actor, and a fixture is exactly that.
     *
     * <p><b>The document goes on the ORG's axis; the person it names stays on the harness's.</b>
     * {@code document} is a SPLIT table routed on {@code org_id} (ADR 0010 §2 row 12) — a non-null org
     * is the tenant's document, NULL would be a personal one — so seeded on the PLATFORM pin this row
     * would land in the personal copy, and {@code documents_list} (which the dispatcher runs on the
     * key's org) would answer an empty list for a document the fixture plainly inserted.
     * {@code person} is platform-tier and belongs where the harness already is.
     */
    private UUID seedDocument(UUID orgId, String name, String storageKey) {
        UUID id = UUID.randomUUID();
        UUID owner = EdgeSeed.person(jdbc, "doc-owner-" + UUID.randomUUID());
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into document (id, org_id, owner_person_id, storage_key, name, content_type,
                                      size_bytes, source, version, created_at)
                values (?, ?, ?, ?, ?, 'application/pdf', 1234, 'UPLOAD', 0, now())
                """, id, orgId, owner, storageKey, name));
        return id;
    }

    /**
     * A ticket a PERSON opened — the only shape the table allows (opener_person_id is NOT NULL) — and
     * its opening message. Both on the tenant's axis: {@code ticket} is TENANT-tier and
     * {@code ticket_message} has no {@code org_id} of its own and follows its ticket (ADR 0010 §2).
     * One span for the pair, because they are one tenant's rows written together.
     */
    private UUID seedTicket(UUID orgId, UUID openerPersonId, String subject, String openingMessage) {
        UUID id = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> {
            jdbc.update("""
                    insert into ticket (id, org_id, opener_person_id, subject, category, priority, status,
                                        first_response_due_at, resolution_due_at, escalated, version, created_at)
                    values (?, ?, ?, ?, 'technical', 'P2', 'OPEN',
                            now() + interval '4 hours', now() + interval '2 days', false, 0, now())
                    """, id, orgId, openerPersonId, subject);
            jdbc.update("""
                    insert into ticket_message (id, ticket_id, author_person_id, body, internal, created_at)
                    values (?, ?, ?, ?, false, now())
                    """, UUID.randomUUID(), id, openerPersonId, openingMessage);
        });
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
