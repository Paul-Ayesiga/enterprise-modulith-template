package ug.co.smsone.mcp.internal;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.webhooks.WebhookAdmin;

/**
 * Reference resources — deliberately drift-free: the permission and event catalogs are RENDERED
 * FROM THE ENUMS every read, and the agent guide ships inside the jar. Nothing here mirrors a file
 * that lives elsewhere in the repo (the classic way reference docs rot).
 */
@Component
class McpResourceCatalog {

    static final String GUIDE_URI = "smsone://guide/agent";
    static final String PERMISSIONS_URI = "smsone://reference/permissions";
    static final String EVENTS_URI = "smsone://reference/webhook-events";

    private final WebhookAdmin webhooks;
    private final String agentGuide;

    McpResourceCatalog(WebhookAdmin webhooks) {
        this.webhooks = webhooks;
        this.agentGuide = readClasspath("mcp/agent-guide.md");
    }

    Map<String, McpStatelessServerFeatures.SyncResourceSpecification> specifications() {
        return Map.of(
                GUIDE_URI, text(GUIDE_URI, "Agent guide",
                        "How this MCP surface works: scoping, errors, pagination, async jobs, secrets.",
                        "text/markdown", (context) -> agentGuide),
                PERMISSIONS_URI, text(PERMISSIONS_URI, "Permission catalog",
                        "Every organization permission code a tool or API key can be gated on.",
                        "text/plain", (context) -> permissionCatalog()),
                EVENTS_URI, text(EVENTS_URI, "Webhook event types",
                        "The event codes webhook subscriptions accept, with what each one means.",
                        "text/plain", (context) -> eventCatalog()));
    }

    private String permissionCatalog() {
        return Arrays.stream(Permission.values())
                .map(Permission::code)
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    private String eventCatalog() {
        return webhooks.eventTypes().stream()
                .map(type -> type.code() + " — " + type.description())
                .collect(Collectors.joining("\n"));
    }

    private static McpStatelessServerFeatures.SyncResourceSpecification text(String uri, String name,
            String description, String mimeType, java.util.function.Function<Object, String> body) {
        McpSchema.Resource resource = McpSchema.Resource.builder(uri, name)
                .description(description)
                .mimeType(mimeType)
                .build();
        return new McpStatelessServerFeatures.SyncResourceSpecification(resource,
                (context, request) -> new McpSchema.ReadResourceResult(
                        List.of(new McpSchema.TextResourceContents(uri, mimeType, body.apply(context)))));
    }

    private static String readClasspath(String location) {
        try {
            return new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Missing bundled resource " + location, ex);
        }
    }
}
