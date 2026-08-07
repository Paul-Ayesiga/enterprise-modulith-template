package ug.co.smsone.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The full protocol loop over real HTTP with a REAL MCP client — initialize, permission-filtered
 * tools/list, tools/call — against real auth (keys seeded into Postgres with the production hash)
 * and the real dispatcher. Complements {@code McpGuardrailsTest} (write guard + org policy, ports
 * stubbed) and {@code McpToolCatalogTest} (catalog invariants).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    /** A write tool exists only here: Phase 0 ships none, but list-filtering needs a gated tool. */
    @TestConfiguration
    static class GatedToolConfig {

        @Bean
        ToolManifest gatedTestManifest() {
            return () -> List.of(new ToolDefinition("webhooktest_touch", "Gated test tool",
                    "Test-only tool gated on webhook:manage.", 1, "test",
                    ToolDefinition.Kind.WRITE, "webhook:manage",
                    ToolDefinition.noArguments(), (context, arguments) -> Map.of("touched", true)));
        }
    }

    @Test
    void aRealClientInitializesListsAndCallsWhoamiWithAnOrgKey() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "loop-key", "org:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            McpSchema.InitializeResult initialized = client.initialize();
            assertThat(initialized.serverInfo().name()).isEqualTo("smsone-platform");
            assertThat(initialized.instructions()).contains("organization your API key belongs to");

            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder("whoami").build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) result.structuredContent();
            // whoami names the two id spaces separately and never merges them: personId is the
            // person behind the call — NULL for a key, because a robot is not a person — and the
            // credential is named by keyId/keyName. The old single "subject" field ("key:<id>")
            // pretended a machine had an identity this platform owns.
            assertThat(payload)
                    .containsEntry("personId", null)
                    .containsEntry("keyId", key.keyId().toString())
                    .containsEntry("keyName", "loop-key")
                    .containsEntry("orgId", orgId.toString())
                    .containsEntry("authKind", "api_key")
                    .containsEntry("permissions", List.of("org:read"));
            // The house rule "every response carries the request id" holds on this surface too.
            assertThat(result.meta()).containsKey("smsone/requestId");
            assertThat((String) result.meta().get("smsone/requestId")).isNotBlank();
        }
    }

    @Test
    void theBearerSkSpellingAuthenticatesLikeTheHeader() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "bearer-key", "org:read");

        try (McpSyncClient client = McpTestSupport.client(port,
                Map.of("Authorization", "Bearer " + key.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder("whoami").build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        }
    }

    @Test
    void toolsListShowsOnlyWhatTheKeysPermissionsAllow() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.SeededKey minimal = McpTestSupport.seedOrgKey(jdbc, orgId, "minimal", "org:read");
        McpTestSupport.SeededKey manager =
                McpTestSupport.seedOrgKey(jdbc, orgId, "manager", "org:read", "webhook:manage");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", minimal.presented()))) {
            client.initialize();
            List<String> visible = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertThat(visible).contains("whoami").doesNotContain("webhooktest_touch");
            // The area codes make the courtesy REAL: an org:read-only key sees none of the five
            // area surfaces (each now gated on its own ticket:/exchange:/search:/subscription:/
            // usage: code), so the listing an agent sees is the catalog it may actually call.
            assertThat(visible).doesNotContain("tickets_list", "ticket_create", "exchange_jobs_list",
                    "exchange_submit", "search_query", "subscription_get", "usage_summary");
        }
        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", manager.presented()))) {
            client.initialize();
            List<String> visible = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertThat(visible).contains("whoami", "webhooktest_touch");
        }
    }

    @Test
    void aHiddenToolCalledDirectlyIsDeniedNotJustHidden() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.SeededKey minimal = McpTestSupport.seedOrgKey(jdbc, orgId, "sneaky", "org:read");

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", minimal.presented()))) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder("webhooktest_touch").build());
            assertThat(result.isError()).isTrue();
            assertThat(McpTestSupport.textOf(result)).contains("FORBIDDEN").contains("webhook:manage");
        }
    }

    @Test
    void noCredentialsIsA401BeforeTheProtocolEverAnswers() {
        try (McpSyncClient client = McpTestSupport.client(port, Map.of())) {
            assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void aGetWithAValidKeyIsA405NotA401() throws Exception {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "get-probe", "org:read");

        // The stateless transport has no server-push stream, so the SDK servlet answers GET with
        // sendError(405). That travels Boot's ERROR dispatch, which must stay permitted in the
        // security chain — or the 405 is rewritten into the entry point's 401 and a remote MCP
        // client (mcp-remote opens exactly this GET after initialize) concludes its key is bad.
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest authed = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("X-Api-Key", key.presented())
                .header("Accept", "text/event-stream")
                .GET().build();
        HttpResponse<String> streamProbe = http.send(authed, HttpResponse.BodyHandlers.ofString());
        assertThat(streamProbe.statusCode()).isEqualTo(405);

        // Without a credential the same GET is still refused at the door, not shown the 405.
        HttpRequest anonymous = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Accept", "text/event-stream")
                .GET().build();
        HttpResponse<String> unauthenticated = http.send(anonymous, HttpResponse.BodyHandlers.ofString());
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
    }

    @Test
    void theOauthDiscoveryDocumentAndChallengeBootstrapNativeConnectors() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        // RFC 9728 metadata is public by definition — it names WHO authorizes /mcp, grants nothing.
        HttpResponse<String> metadata = http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/.well-known/oauth-protected-resource"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(metadata.body())
                .contains("\"resource\"").contains("/mcp")
                .contains("\"authorization_servers\"").contains("\"scopes_supported\"");

        // An anonymous /mcp call is refused WITH the challenge a connector bootstraps from.
        HttpRequest anonymous = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .build();
        HttpResponse<String> refused = http.send(anonymous, HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.headers().firstValue("WWW-Authenticate").orElse(""))
                .contains("resource_metadata")
                .contains("/.well-known/oauth-protected-resource");
    }

    @Test
    void aRevokedKeyIsA401OnItsVeryNextRequest() {
        UUID orgId = UUID.randomUUID();
        McpTestSupport.SeededKey key = McpTestSupport.seedOrgKey(jdbc, orgId, "revoked", "org:read");
        McpTestSupport.revoke(jdbc, key.keyId());

        try (McpSyncClient client = McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()))) {
            assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
        }
    }

}
