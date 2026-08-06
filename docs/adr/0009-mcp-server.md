# ADR 0009 — MCP server: the agent protocol surface

## Context

AI agents (Claude Code/Desktop, the Claude API's MCP connector, Managed Agents, other MCP clients)
need to read and operate a tenant's slice of the platform. The Model Context Protocol is the
standard wire for that — tools an agent discovers and calls, resources it reads, prompts it renders.
The platform already owns the three hard parts an agent surface needs: org-scoped machine
credentials (`apikeys`), a fixed permission vocabulary (`organization.Permission`), and module APIs
behind enforced boundaries. The question was where the MCP server lives and how much of the
existing machinery it reuses.

## Decision

An `mcp` module **inside the modulith** serves MCP at `/mcp` — the official MCP Java SDK
(`io.modelcontextprotocol.sdk:mcp` 2.0.0, Jackson 3 like Boot 4.1) with the **stateless streamable
HTTP** servlet transport registered via `ServletRegistrationBean`. MCP is treated as a **second
protocol surface over the same module ports the REST controllers use**: the module owns tool
definitions (one manifest per module area, aggregated by collection injection) and one dispatch
pipeline; it owns no business capability. Where a capability had no cross-module port, the port was
added in the **owner's** API package, implemented by the existing internal service.

Auth is the existing API key, now accepted in two spellings (`X-Api-Key` and
`Authorization: Bearer sk_…` — the only spelling remote MCP clients can send), at both tiers: the
gateway routes `sk_` bearers to key introspection, the modulith's key filter consumes them before
the JWT machinery sees them. Tools are **org-implicit** (the org comes from the principal; no tool
takes an `orgId`), gated on the **same permission code as their REST twin**, discovered filtered
(`tools/list` shows only what the key allows) and re-checked per call through
`ApiPermissionEvaluator` — fail closed. A single dispatcher enforces, in order: permission →
per-org IP allowlist (`access.OrgSecurityPolicies`) → write guard (RESTRICT maintenance windows and
PAUSED subscriptions, via two new read ports) → the handler; mutations audit as
`mcp.tool_invoked`, everything meters as `smsone.mcp.tool.calls{tool,kind,outcome}`.
`app.mcp.enabled=false` refuses with a named JSON-RPC error rather than unregistering the servlet.

## Why

- **In-process beats a sidecar.** A separate MCP service would re-solve auth, entitlements and data
  access over HTTP for no isolation gain. In-process, every invariant the services enforce — the
  escalation guard, SSRF guard, entitlement caps, last-owner lock, audit attribution — fires for
  agent calls exactly as for REST, because it is literally the same code.
- **Stateless transport is what makes N replicas safe.** No session map, no sticky routing, no SSE
  stream to pin — each JSON-RPC POST stands alone, which is also where the protocol is going (the
  2026-07-28 spec RC removes streamable-HTTP sessions). The accepted loss — no server-initiated
  messages — costs nothing for tools/resources/prompts.
- **A servlet, not a controller,** keeps the JSON:API envelope and the OpenAPI export untouched:
  JSON-RPC never meets the MVC advice chain or springdoc.
- **The key's permission subset is the scope model.** No second vocabulary to drift; `tools/list`
  filtering and the call-time check answer the same question the REST evaluator answers.
- **Ports in the owner's package** (the `subscription.Subscriptions` precedent) mean the ~13 ports
  added for MCP are ordinary module API — usable by any future surface, not MCP plumbing.

## Consequences

- `/mcp` sits outside the `/api/`-keyed filters, so its cross-cutting behavior is wired explicitly:
  the rate-limit filter's matcher covers `/mcp` (per-key `mcp` tier), and the dispatcher carries the
  IP-allowlist and write-gate decisions through ports into the same modules the filters consult —
  two surfaces, one authority, by construction rather than by discipline.
- The escalation guard and the exchange permission check gained a **machine branch**: an API key's
  minted permission subset is its held set (capped at mint by a human who held those permissions),
  so key-driven grants stay un-escalatable instead of impossible.
- The gateway carries a dedicated `edge-mcp` policy (60 s response timeout, 2 MiB body cap,
  authenticated, rate-limited) — `edge-api`'s 15 s would cut off legitimate tool calls.
- No tables, no events, no migrations: the surface is stateless glue; audit rides `audit_log`.
- OAuth 2.1 for browser-consented connectors (claude.ai) is designed-for but deferred: auth is a
  request-time concern in one filter, and the tool layer never reads credentials.

Plan: `docs/plans/MCP_PLAN.md` · guide: `docs/guides/mcp-guide.html` · tests:
`ug.co.smsone.mcp.internal.*`, `McpMemberWriteToolsTest`, gateway `ShippedRouteTableTest`.
