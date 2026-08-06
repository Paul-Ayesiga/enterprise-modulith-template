package ug.co.smsone.mcp.internal;

import java.util.List;

/**
 * One module area's contribution to the catalog — {@code FoundationToolManifest},
 * {@code OrganizationToolManifest}, … — aggregated by {@link McpToolRegistry} through collection
 * injection. Manifests live HERE, in the mcp module, not in the modules they cover: modules own
 * capability ports; mcp owns the tool definitions that speak MCP over them. The dependency arrow
 * stays {@code mcp → module API} only.
 */
public interface ToolManifest {

    List<ToolDefinition> tools();
}
