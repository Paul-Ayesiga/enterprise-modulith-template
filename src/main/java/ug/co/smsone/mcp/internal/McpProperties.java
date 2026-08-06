package ug.co.smsone.mcp.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MCP surface switches. {@code enabled=false} REFUSES (a named JSON-RPC error on every request)
 * rather than unregistering the servlet — the impersonation kill-switch rule: a 404 would be
 * indistinguishable from a typo'd path or version skew, and agents should see why they were cut off.
 */
@ConfigurationProperties("app.mcp")
public record McpProperties(@DefaultValue("true") boolean enabled) {
}
