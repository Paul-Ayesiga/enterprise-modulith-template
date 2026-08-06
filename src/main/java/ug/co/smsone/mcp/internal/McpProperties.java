package ug.co.smsone.mcp.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MCP surface switches. {@code enabled=false} REFUSES (a named JSON-RPC error on every request)
 * rather than unregistering the servlet — the impersonation kill-switch rule: a 404 would be
 * indistinguishable from a typo'd path or version skew, and agents should see why they were cut off.
 *
 * <p>{@code publicBaseUrl} is the EXTERNAL origin agents reach this surface on (the gateway in every
 * shipped topology) — it names the RFC 9728 resource id and the challenge URL, so it must match what
 * a connector actually dials. {@code audience} is the JWT audience the OAuth path demands; the
 * Keycloak {@code mcp} client scope stamps it (Phase 7).
 */
@ConfigurationProperties("app.mcp")
public record McpProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:28090") String publicBaseUrl,
        @DefaultValue("smsone-mcp") String audience) {
}
