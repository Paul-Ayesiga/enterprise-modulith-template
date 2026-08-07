package ug.co.smsone.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phases 5–6: prompts render with arguments, and the reference resources round-trip — with the
 * drift-free property proven the only way it can be: the catalogs contain what the ENUMS contain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpAgentContentTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void promptsListAndRenderWithArguments() {
        try (McpSyncClient client = client()) {
            client.initialize();
            assertThat(client.listPrompts().prompts())
                    .extracting(McpSchema.Prompt::name)
                    .contains("diagnose_failed_webhooks", "usage_review");

            McpSchema.GetPromptResult rendered = client.getPrompt(
                    McpSchema.GetPromptRequest.builder("diagnose_failed_webhooks")
                            .arguments(Map.of("subscription_id", "sub-42"))
                            .build());
            assertThat(rendered.messages()).hasSize(1);
            McpSchema.TextContent text = (McpSchema.TextContent) rendered.messages().get(0).content();
            assertThat(text.text()).contains("For subscription sub-42").contains("webhook_redeliver");
        }
    }

    @Test
    void referenceResourcesReadBackTheLiveCatalogs() {
        try (McpSyncClient client = client()) {
            client.initialize();
            assertThat(client.listResources().resources())
                    .extracting(McpSchema.Resource::uri)
                    .contains(McpResourceCatalog.GUIDE_URI, McpResourceCatalog.PERMISSIONS_URI,
                            McpResourceCatalog.EVENTS_URI);

            assertThat(read(client, McpResourceCatalog.PERMISSIONS_URI))
                    .contains("member:invite").contains("webhook:manage");
            assertThat(read(client, McpResourceCatalog.EVENTS_URI))
                    .contains("org.member.added");
            assertThat(read(client, McpResourceCatalog.GUIDE_URI))
                    .contains("whoami").contains("page_after");
        }
    }

    private String read(McpSyncClient client, String uri) {
        McpSchema.ReadResourceResult result =
                client.readResource(McpSchema.ReadResourceRequest.builder(uri).build());
        return ((McpSchema.TextResourceContents) result.contents().get(0)).text();
    }

    private McpSyncClient client() {
        McpTestSupport.SeededKey key =
                McpTestSupport.seedOrgKey(jdbc, UUID.randomUUID(), "content", "org:read");
        return McpTestSupport.client(port, Map.of("X-Api-Key", key.presented()));
    }
}
