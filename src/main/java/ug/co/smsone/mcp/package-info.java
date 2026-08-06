/**
 * MCP module: the agent protocol surface. Serves the Model Context Protocol over stateless
 * streamable HTTP at {@code /mcp} — a second protocol surface over the same module APIs the REST
 * controllers use, so every permission, guard and audit rule exists once. Tools are org-implicit
 * (the org comes from the authenticated API key, never a parameter), permission-gated with the
 * REST vocabulary ({@code organization.Permission}), and discovered filtered: {@code tools/list}
 * shows only what the caller may call. The module owns tool definitions and dispatch; it owns NO
 * business capability — handlers delegate to other modules' API ports. Deliberately stateless: no
 * tables, no events, no sessions.
 */
@org.springframework.modulith.ApplicationModule(displayName = "MCP")
package ug.co.smsone.mcp;
