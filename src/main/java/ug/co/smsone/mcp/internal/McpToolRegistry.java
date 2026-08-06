package ug.co.smsone.mcp.internal;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.Permission;

/**
 * The aggregated tool catalog. Validates every manifest at startup — a malformed tool (duplicate or
 * non-snake_case name, unknown permission code, missing metadata) fails boot, not a 4am agent call —
 * and renders definitions as SDK tools with the safety annotations and {@code _meta} the plan
 * promises clients (version + area).
 */
@Component
class McpToolRegistry {

    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");

    private final Map<String, ToolDefinition> byName;

    McpToolRegistry(List<ToolManifest> manifests) {
        Map<String, ToolDefinition> catalog = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (ToolManifest manifest : manifests) {
            for (ToolDefinition tool : manifest.tools()) {
                validate(tool, problems);
                if (catalog.putIfAbsent(tool.name(), tool) != null) {
                    problems.add("duplicate tool name '" + tool.name() + "'");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("MCP tool catalog invalid: " + String.join("; ", problems));
        }
        this.byName = Map.copyOf(catalog);
    }

    List<ToolDefinition> all() {
        return List.copyOf(byName.values());
    }

    Optional<ToolDefinition> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** The wire shape: annotations carry the safety class, {@code _meta} the version and area. */
    static McpSchema.Tool toMcpTool(ToolDefinition tool) {
        return McpSchema.Tool.builder(tool.name())
                .title(tool.title())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .annotations(McpSchema.ToolAnnotations.builder()
                        .title(tool.title())
                        .readOnlyHint(tool.kind() == ToolDefinition.Kind.READ)
                        .destructiveHint(tool.kind() == ToolDefinition.Kind.DESTRUCTIVE)
                        .idempotentHint(tool.kind() == ToolDefinition.Kind.READ)
                        .openWorldHint(false)
                        .build())
                .meta(Map.of("smsone/toolVersion", tool.version(), "smsone/area", tool.tag()))
                .build();
    }

    private static void validate(ToolDefinition tool, List<String> problems) {
        if (!TOOL_NAME.matcher(tool.name()).matches()) {
            problems.add("tool name '" + tool.name() + "' is not <area>_<verb> snake_case");
        }
        if (tool.version() < 1) {
            problems.add("tool '" + tool.name() + "' has version < 1");
        }
        if (tool.tag() == null || tool.tag().isBlank()) {
            problems.add("tool '" + tool.name() + "' has no area tag");
        }
        if (tool.description() == null || tool.description().isBlank()) {
            problems.add("tool '" + tool.name() + "' has no description");
        }
        if (tool.requiredPermission() != null) {
            try {
                Permission.fromCode(tool.requiredPermission());
            } catch (IllegalArgumentException ex) {
                problems.add("tool '" + tool.name() + "' requires unknown permission '"
                        + tool.requiredPermission() + "'");
            }
        }
    }
}
