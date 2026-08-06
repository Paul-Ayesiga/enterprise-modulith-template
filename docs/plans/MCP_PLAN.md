# MCP Server Plan — agents talk to the platform

How the platform grows an **MCP (Model Context Protocol) server surface** so AI agents — Claude Code,
Claude Desktop, the Claude API's MCP connector, Managed Agents, and any other MCP client — can read
and operate a tenant's slice of the platform with the same authorization the REST API enforces. This
is a **plan** — no MCP code exists yet; it is written to be approved before Phase 0 starts (the
repo's plan-first rule). The decision record lands as ADR 0009 with Phase 0.

Direction set at approval time: **all four capability groups are in scope** (account & subscription,
webhooks, support & documents, exchange & search), the **full write surface is the target** — the
foundation is built for writes from day one — but delivery is **module by module**, one gated slice
at a time. Client auth is **API keys only** in v1; OAuth for browser-consented connectors is a
flagged later phase.

## Why an MCP surface, and why in the modulith

MCP is to agents what REST is to programs: a standard wire protocol for discovering and invoking
capabilities. The platform already has the three hard parts an agent surface needs — machine
credentials scoped to a tenant (`apikeys`), a fixed permission vocabulary (`organization.Permission`),
and module APIs behind enforced boundaries. The MCP server is deliberately **a second protocol
surface over the same module APIs the REST controllers use** — a new `mcp` module in the modulith,
not a separate deployable. A separate service would re-solve auth, entitlements, and data access over
HTTP for no isolation benefit; in-process, every existing invariant (escalation guard, tenant
equality, fail-closed permissions, audit attribution) rides along because MCP tools call the same
services.

---

## 1. Locked decisions

| Decision | Choice | Rationale |
|---|---|---|
| SDK | **MCP Java SDK 2.0.0** — `io.modelcontextprotocol.sdk:mcp` via `mcp-bom:2.0.0` | Official SDK, GA (verified on Maven Central 2026-08-06). The `mcp` bundle = `mcp-core` + `mcp-json-jackson3` — **Jackson 3 by default**, matching Boot 4.1's `tools.jackson`. No Spring AI dependency; the Spring transports moved there and we don't need them. |
| Transport | **Streamable HTTP, stateless** (`HttpServletStatelessServerTransport`) mounted at `/mcp` via `ServletRegistrationBean` | Plain JSON-RPC request/response POSTs — no `Mcp-Session-Id`, no SSE listening stream, no in-memory session map. That is what makes it safe across **modulith ×3 replicas** with no sticky sessions. It is also where the protocol is going: the 2026-07-28 spec RC removes sessions from streamable HTTP entirely. |
| What we give up (knowingly) | No server-initiated messages: no sampling, elicitation, subscriptions, or progress notifications | None are needed for tools/resources/prompts, which are all request/response. If a future phase wants them, the stateful transport is a config swap, not a redesign. |
| Mount path | **`/mcp`** — a protocol root beside `/api/v1`, not under it | Matches remote-MCP convention (`https://api.smsone.co.ug/mcp`) and keeps JSON-RPC out of the tenant REST conventions. Being a servlet (not an MVC controller), `EnvelopeResponseBodyAdvice` never touches it and springdoc never scans it — the envelope and OpenAPI stay exactly what they are: the REST contract. The `/api/`-keyed cross-cutting filters don't apply automatically; §3 wires the ones that matter deliberately. |
| Client auth (v1) | **API keys** — `X-Api-Key: sk_…` as today, **plus** `Authorization: Bearer sk_…` accepted on any path | The existing filter already authenticates `X-Api-Key` path-agnostically. Remote MCP clients (Claude API `mcp_servers.authorization_token`, Managed Agents `static_bearer` vault credentials) send `Authorization: Bearer`; the `sk_` prefix is unambiguous vs. a JWT (`eyJ…`), so `ApiKeyAuthenticationFilter` learns to consume bearer tokens with that prefix before the JWT filter sees them. A JWT on `/mcp` keeps working too — nothing forbids a human-token client — but v1's documented target is keys. |
| Authorization | The key's permission subset **is** the tool scope model — no new vocabulary | Keys are minted as a subset of the minter's own `organization.Permission` codes. Every tool declares the **same permission code its REST twin requires**; `tools/list` filters to what the caller holds, `tools/call` re-checks through `ApiPermissionEvaluator` (the existing machine branch: strict org equality + permission containment, fail closed). |
| Tenancy | **Tools are org-implicit** — no tool takes an `orgId` argument | The org comes from the authenticated principal (`ApiKeyPrincipal.orgId`). An agent can never address another tenant by parameter; the strict-equality check holds trivially. |
| Module calls | Tools call **module ports that delegate to the existing internal services** — never re-implementations | `PermissionEscalationGuard`, `SafeOutboundUrl`, pessimistic last-owner locks, Keycloak-outside-transaction ordering and audit writes all live in those services; delegation keeps every invariant. Where a capability has no port today, the port is added in the **owner's API package** (the `subscription.Subscriptions` precedent), implemented by the existing internal service — useful to any future module, not just MCP. |
| Writes | Full write surface designed in from Phase 0: a single **write guard** (maintenance window + paused-subscription read-only + audit) wraps every mutating tool | The `/api/`-keyed `MaintenanceFilter` and `SubscriptionAccessFilter` never see `/mcp`; re-implementing their decisions per-tool would drift. One guard, fed by two small new ports, mirrors their semantics in one place. |
| Machine grants | For grant paths (member invite/re-role, exchange submit), an API key's **minted permission subset is its held set** — the escalation guard and the exchange permission check gained a machine branch | The subject-based membership lookup resolves to nothing for `key:<id>`, which would make key-driven grants impossible rather than safe. The subset was capped at mint by a human who HELD those permissions, so the no-escalation invariant carries transitively; beyond-subset grants still refuse before any side effect. |
| Errors | `ApiException` subclasses map to MCP tool errors (`isError: true`) carrying the wire `ErrorCode` name, the curated `detail`, and the **requestId** | Same contract discipline as REST: never a stack trace, never an internal message; the requestId is on every response (house rule) — `RequestIdFilter` runs at `HIGHEST_PRECEDENCE`, so it is already in the MDC. Malformed JSON-RPC is the SDK's problem and stays protocol-level. |
| Naming | Tools `snake_case`, `<area>_<verb>`: `org_get`, `members_list`, `webhook_redeliver` | MCP ecosystem convention; the area prefix groups the catalog in client UIs. |
| Tool metadata | Every tool declares `version` (int, starts at 1), `tags` (its module area), and a safety `kind` — **READ / WRITE / DESTRUCTIVE** | Built in before tools accumulate. `kind` maps onto the spec's tool annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`) so clients that gate destructive calls get the signal on the wire; `version` + `tags` ride in `_meta`. Evolution rule: a tool is **never renamed** — breaking schema changes bump `version` and note it in the description; additive changes don't. |
| `ToolContext` | Handlers are `execute(ToolContext, args)` — context carries org id, principal, held permissions, requestId, and the audit/metrics hooks | Resolved once per call in the dispatcher, not re-looked-up per handler. With 25+ tools planned, per-handler `SecurityContextHolder` spelunking would be the drift vector. |
| Tool manifests | One `ToolManifest` per module area — `FoundationToolManifest`, `OrganizationToolManifest`, `WebhookToolManifest`, … — **all inside `mcp/internal/catalog/`**, aggregated by collection injection | Modules own their *capability ports*; `mcp` owns the *tool definitions* that speak MCP over them. Manifests inside the owning modules would make every business module compile-depend on the protocol surface — the wrong arrow. This keeps the registry small-file, low-conflict, and the dependency direction `mcp → module API` only. |
| Payload ceiling | Collection tools cap at `page_size ≤ 100` (default 25) with cursor continuation; a single tool result stays under **256 KiB**; bulk data moves as download URLs | Agents page like REST clients page (ADR 0002 cursors). A tool that would exceed the ceiling is mis-designed — split it or link it. |
| Persistence | **None.** No tables, no migration | The MCP layer is stateless glue. Audit uses the existing `audit_log` via the `AuditLog` port; usage metrics are Micrometer counters. |

---

## 2. Architecture

```
agent (Claude Code / Desktop / API connector / Managed Agents / any MCP client)
  │  POST /mcp  (JSON-RPC 2.0 over streamable HTTP, stateless)
  │  Authorization: Bearer sk_…   or   X-Api-Key: sk_…
  ▼
gateway  — route: /mcp/** → modulith · policy: edge-mcp (authenticated, rate-limited,
  │        60s response timeout, 2 MiB body cap — NOT edge-api's 15s)
  ▼
modulith servlet chain — RequestIdFilter → ApiKeyAuthenticationFilter (now also Bearer sk_)
  │        → OrgMdcFilter → RateLimitFilter (matcher widened to /mcp)
  ▼
/mcp servlet — HttpServletStatelessServerTransport (MCP SDK 2.0)
  │
  ├─ tools/list  → McpToolRegistry: catalog filtered to the caller's permissions
  ├─ tools/call  → McpToolDispatcher:
  │     1. permission check (ApiPermissionEvaluator — fail closed)
  │     2. org security policy (IP allowlist via access.OrgSecurityPolicies port)
  │     3. write guard, mutating tools only (maintenance + subscription pause ports)
  │     4. invoke module port  →  existing internal service
  │     5. audit (mutations) + Micrometer (all) + requestId onto the result
  ├─ resources/* → OpenAPI spec, api-guide (Phase 5)
  └─ prompts/*   → curated prompt templates (Phase 5)
```

New components, all inside `ug.co.smsone.mcp` (API package empty at first; everything `internal`):

| Component | Job |
|---|---|
| `McpEndpointConfig` | Builds the SDK server + stateless transport, registers the servlet at `/mcp` (async-enabled), wires the Jackson 3 mapper |
| `ToolDefinition` / `ToolManifest` | A definition = name, title, description, input schema, `version`, `tags`, `kind` (READ/WRITE/DESTRUCTIVE), required permission, handler. A manifest = one module area's definitions (`mcp/internal/catalog/*ToolManifest`); the registry aggregates all manifests via collection injection |
| `ToolContext` | Per-call context handed to every handler: org id, principal subject, held permissions, requestId — resolved once in the dispatcher |
| `McpToolRegistry` | Aggregates manifests into the catalog; `tools/list` renders only what the caller may call, with spec annotations + `_meta` (version, tags) on the wire |
| `McpToolDispatcher` | The five-step pipeline above; the **only** place auth, policy, guard, audit and metrics happen — handlers are `execute(ToolContext, args)` pure delegations |
| `McpWriteGuard` | Refuses mutations during a maintenance window or on a paused subscription — same outcomes as the two `/api/` filters, sourced from the same modules via new ports |
| `McpErrorMapper` | `ApiException` → tool error content (`code`, `detail`, `requestId`); anything unexpected → one generic message + ERROR log with requestId (mirror of `GlobalExceptionHandler.handleUnexpected`) |

### 3. Cross-cutting wiring — what `/mcp` gets, and how

The six `/api/`-keyed servlet filters do not match `/mcp`. Each one is handled deliberately rather
than inherited accidentally:

| Concern (`/api/` filter) | On `/mcp` | How |
|---|---|---|
| Request id (`RequestIdFilter`) | applies already | path-agnostic, `HIGHEST_PRECEDENCE` |
| Auth (security chain) | applies already | one chain, `.anyRequest().authenticated()`; API key + JWT both work |
| Rate limiting (`RateLimitFilter`, order 0) | **inherit** | widen its matcher to `/mcp` — same tenant → subject → IP buckets; the gateway's `edge-mcp` token bucket sits in front as the first line |
| Impersonation (`ImpersonationFilter`) | **not applicable** | header is only honored on `/api/`; operator agents are a non-goal (§8) |
| Idempotency (`IdempotencyFilter`) | **not needed** | keyed on an HTTP header MCP clients don't send; write idempotency is the services' business-identity dedup, as everywhere else (AGENTS §4.4) |
| Provisioning gate | **not applicable to keys** | the gate provisions JWT *users*; `ApiKeyPrincipal` isn't one. A JWT caller on `/mcp` skips the gate but still resolves permissions through membership — an unprovisioned subject holds none and every tool denies. Fail closed either way |
| Org security policy (`OrgPolicyEnforcementFilter`) | **enforce in dispatcher** | the filter matches `/api/v1/orgs/<uuid>` URLs, which `/mcp` never is. The dispatcher asks `access.OrgSecurityPolicies` (public API) directly: IP allowlist applies to every call; MFA/session-age don't apply to keys by construction — unchanged |
| Maintenance + paused subscription (write blocks) | **enforce in `McpWriteGuard`** | two new read ports (§5); guard runs only for tools marked `WRITE` |

---

## 4. Tool catalog — the full surface, module by module

Permission codes are copied from the REST controllers' `hasPermission` annotations — the MCP tool and
its REST twin are gated identically, always. `Port` names are indicative; final naming follows the
owner module's voice at implementation.

**Foundation (Phase 0)**

| Tool | Kind | Permission | Backing |
|---|---|---|---|
| `whoami` | read | *(any authenticated)* | `CurrentUserProvider` + key principal: org id, key name, held permissions, platform standing — the agent's "who am I, what may I do" bootstrap |

**Account & standing (Phase 1)** — modules `organization`, `subscription`, `billing`

| Tool | Kind | Permission | Backing |
|---|---|---|---|
| `org_get` | read | `org:read` | NEW `organization.OrgDirectory` |
| `org_update` | write | `org:update` | NEW port method → `OrganizationService` |
| `members_list` | read | `member:read` | NEW `organization.OrgMembers` |
| `member_invite` | write | `member:invite` | same port → `MemberService.invite` (escalation guard rides along) |
| `member_role_assign` | write | `member:role:assign` | same port → `MemberService` (guard + last-owner lock ride along) |
| `member_remove` | **destructive** | `member:remove` | same port → `MemberService.remove` |
| `roles_list` | read | `role:read` | NEW `organization.OrgRoles` (list only — role CRUD stays REST/human; §8) |
| `subscription_get` | read | `subscription:read` | NEW `subscription.SubscriptionOverview` (plan, status, trial/pause state, entitlements + limits) |
| `usage_summary` | read | `usage:read` | NEW `billing.UsageSummaries` (period usage, plan position; no payment data) |

**Webhooks (Phase 2)** — full management, matching REST's single `webhook:manage` gate

| Tool | Kind | Permission | Backing |
|---|---|---|---|
| `webhooks_list` | read | `webhook:manage` | NEW `webhooks.WebhookAdmin` |
| `webhook_get` | read | `webhook:manage` | same port (subscription + recent delivery outcomes) |
| `webhook_deliveries` | read | `webhook:manage` | same port (delivery log w/ status, attempts, last error — cursor-paged) |
| `webhook_create` | write | `webhook:manage` | same port → service (`SafeOutboundUrl` rides along; secret shown once) |
| `webhook_update` | write | `webhook:manage` | same port |
| `webhook_rotate_secret` | write | `webhook:manage` | same port (new secret shown once) |
| `webhook_delete` | **destructive** | `webhook:manage` | same port |
| `webhook_redeliver` | write | `webhook:manage` | same port → NEW fenced re-queue of FAILED deliveries (REST twin: `POST …/deliveries/{id}/redeliver`, 202) |

**Support & documents (Phase 3)** — modules `support`, `document`

| Tool | Kind | Permission | Backing |
|---|---|---|---|
| `tickets_list` / `ticket_get` | read | `ticket:read` | NEW `support.SupportDesk` |
| `ticket_create` | write | `ticket:write` | same port → `SupportService` (mirrors REST's gate; opener = key subject) |
| `ticket_messages` | read | `ticket:read` | same port (tenant view only — internal notes never cross) |
| `ticket_reply` | write | `ticket:write` | same port |
| `documents_list` / `document_get` | read | `document:read` | NEW `document.DocumentDirectory` (metadata only) |
| `document_download_url` | read | `document:read` | same port (short-lived presigned URL; bytes never transit MCP) |
| `document_delete` | **destructive** | `document:manage` | same port (bytes now, row soft) |

*As-built delta:* `document_register` was dropped — registration is a multipart byte upload
(`shared.document.Documents` takes a storage key that only the upload path mints), and binary
transfer through MCP is a §8 non-goal. Agents read documents; humans and REST put them in.

**Exchange & search (Phase 4)** — modules `exchange`, `search`

| Tool | Kind | Permission | Backing |
|---|---|---|---|
| `exchange_handlers` | read | `exchange:read` | NEW `exchange.ExchangeJobs` (the movable datasets + their permissions/formats) |
| `exchange_jobs_list` / `exchange_job_get` | read | `exchange:read` | same port |
| `exchange_submit` | write | `exchange:submit` **+ the handler's own export permission** | same port → `ExchangeService` (async — poll `exchange_job_get`) |
| `exchange_cancel` | write | `exchange:submit` | same port (finished jobs answer a conflict) |
| `exchange_result_url` / `exchange_error_report_url` | read | `exchange:read` | same port (short-lived artifact URLs) |
| `search_query` | read | `search:query` | NEW `search.SearchQueries` → `SearchQueryService` |

Collection-shaped results reuse the cursor discipline: tools take optional `page_size` / `page_after`
arguments and return `next_cursor` — same keyset semantics as REST, no totals (ADR 0002).

### 5. New ports inventory (the real cross-module surface)

Ports in the **owner's** API package, implemented by the existing internal service, package-private
impl. Grouped by delivering phase:

| Phase | Port | Owner | Consumed by |
|---|---|---|---|
| 0 | `maintenance.MaintenanceState` (`isWriteBlocked(orgId)`) | maintenance | `McpWriteGuard` |
| 0 | `subscription.SubscriptionGate` (`isWriteBlocked(orgId)`) — or fold into `SubscriptionOverview` | subscription | `McpWriteGuard` |
| 1 | `organization.OrgDirectory`, `organization.OrgMembers`, `organization.OrgRoles` | organization | mcp |
| 1 | `subscription.SubscriptionOverview` | subscription | mcp |
| 1 | `billing.UsageSummaries` | billing | mcp |
| 2 | `webhooks.WebhookAdmin` | webhooks | mcp |
| 3 | `support.SupportDesk`, `document.DocumentDirectory` | support, document | mcp |
| 4 | `exchange.ExchangeJobs`, `search.SearchQueries` | exchange, search | mcp |

`mcp` depends on other modules' API packages only — `ModularityTests` enforces it like everywhere
else. Known trap re-stated: `settings/internal` has public classes that compile cross-module and then
fail `ApplicationModules.verify()` — MCP never touches them.

---

## 6. Phases

Each phase leaves a **runnable, useful** MCP server, gates on real containers, and carries its own
docs duties (§7 table). No phase closes with deferred items inside it.

### Phase 0 — Foundation (the first slice)

**Focus.** A live, authenticated, observable `/mcp` endpoint with one tool, the full dispatch
pipeline (permission check, IP-allowlist policy, write guard, audit, metrics, error mapping), the
gateway route, and the test harness — so every later phase is *only* ports + catalog entries.

**Deliverables**

- `gradle/libs.versions.toml`: `mcp-bom` 2.0.0 platform + `mcp` bundle (+ `mcp-test` if it earns its
  place in the harness); catalog-style, no inline coordinates.
- `ug.co.smsone.mcp` module: `package-info` (@ApplicationModule), `McpEndpointConfig` (stateless
  streamable servlet at `/mcp`, async enabled), `McpToolRegistry`, `McpToolDispatcher`,
  `McpWriteGuard` (+ the two Phase-0 ports it consumes), `McpErrorMapper`, `whoami`.
- `ApiKeyAuthenticationFilter`: accept `Authorization: Bearer sk_…` (prefix-gated; JWTs untouched).
- `RateLimitFilter`: matcher widened to `/mcp`.
- Server identity + instructions: server name/version from build info, an `instructions` string that
  tells agents what the platform is and how tools are scoped (org-implicit, permission-filtered).
- Observability, dashboard-shaped from day one: `smsone.mcp.tool.calls{tool,kind,outcome}` counter +
  duration timer, where `outcome` ∈ `ok | denied | guard_maintenance | guard_subscription | error` —
  enough for "top tools / slowest tools / denied tools / failure mix" panels. **Caller identity is
  deliberately not a metric tag** (per-key tags are unbounded cardinality); who-called-what lives in
  the structured logs (requestId + `key:<id>` already in MDC) and, for mutations, in `audit_log` via
  action `mcp.tool_invoked` (actor = key subject, unchanged attribution rules).
- Gateway: `/mcp/**` route → modulith + `edge-mcp` policy (authenticated, rate-limited, 60 s
  response timeout, 2 MiB cap); `ShippedRouteTableTest` updated.
- `.mcp.json` at the repo root (streamable HTTP → `http://localhost:28080/mcp`,
  `X-Api-Key: ${SMSONE_API_KEY}`) — the server is dogfoodable from Claude Code on day one.
- Tests (§7): the full-loop harness + auth/permission/guard contract tests.

**Gate.** A real MCP client (SDK streamable-HTTP client, `RANDOM_PORT` boot, real Postgres) performs
`initialize` → `tools/list` → `tools/call whoami` with a minted org key — and the negative half:
no credentials → 401; revoked key → 401; `tools/list` under a minimal-permission key shows only
`whoami`; a write tool invoked during a simulated maintenance window / paused subscription refuses.

### Phase 1 — Account & standing (`organization` + `subscription` + `billing`)

**Focus.** The "who am I, where do I stand, manage my people" slice — reads and writes together.

**Deliverables.** The six Phase-1 ports; the nine Phase-1 tools; audit rows for the four member/org
writes; catalog docs (api-guide Part F section F2).

**Gate.** Full-loop tests: key with `member:invite` invites via MCP and the membership exists (and
Keycloak gateway mock interactions match the REST path's); key *without* it sees no `member_invite`
in `tools/list` **and** a direct `tools/call` denies before any side effect (`should(never())` on the
provisioning port — the same negative-assertion discipline as `OrgRbacApiTest`); self-promotion via
`member_role_assign` fails through the escalation guard exactly as REST does.

### Phase 2 — Webhooks

**Focus.** Full webhook management — the highest-leverage agent use case (diagnose + fix integration
failures end to end).

**Deliverables.** `WebhookAdmin` port; seven tools; `SafeOutboundUrl` asserted on the MCP create/update
path; delivery-log reads cursor-paged.

**Gate.** Agent-shaped test: create subscription → observe a failed delivery (real queue, test
receiver) → `webhook_deliveries` shows the failure → `webhook_redeliver` re-enqueues → delivery
succeeds. Plus: `webhook_create` with a private-network URL refuses (SSRF guard proof).

### Phase 3 — Support & documents

**Deliverables.** `SupportDesk` + `DocumentDirectory` ports; seven tools; document reads return
metadata + short-lived download URLs (bytes never transit MCP).

**Gate.** Ticket round-trip (create → list → comment) under `ticket:write`/`ticket:read`; `documents_list` under
`document:read`; `document_register` denied without `document:manage`; download URL actually fetches
from SeaweedFS in the test.

### Phase 4 — Exchange & search

**Deliverables.** `ExchangeJobs` + `SearchQueries` ports; five tools; submit→poll→result flow
documented as the async pattern for agents.

**Gate.** Submit an export via MCP → worker completes it (real containers, worker re-enabled as the
existing exchange tests do) → `exchange_job_get` reports completion → `exchange_result` link
downloads. `search_query` returns seeded results org-scoped only.

### Phase 5 — Agent DX: prompts + portal

**Deliverables.** MCP **prompts**: `diagnose_failed_webhooks`, `usage_review` (curated, few);
developer-portal page "Connect an agent" (mint a key → paste one of three client configs: Claude
Code / Claude API connector / Managed Agents vault); `LOCAL_ACCESS.md` section.

**Gate.** Prompts render with arguments in the harness; portal page reviewed against a real Claude
Code connection.

### Phase 6 — Resources (deliberately last)

MCP **resources**: `docs/openapi/openapi.yaml` and the api-guide, so agents can read the REST
contract they're standing beside. Postponed behind every tool phase on purpose — resources are
documentation, and until the tool surface stabilizes they are maintenance; tools are where the value
is.

**Gate.** `resources/list` + `resources/read` round-trip in the harness.

### Phase 7 — OAuth for consented connectors (flagged, not v1)

claude.ai custom connectors require the MCP OAuth 2.1 flow (protected-resource metadata → Keycloak
as the authorization server, dynamic client registration). Designed-for but explicitly out of v1;
gets its own plan addendum when scheduled. Nothing in Phases 0–6 blocks it: auth is a request-time
concern in one filter, and the tool layer never looks at credentials.

### Phase 8 — permission vocabulary deepening (shipped 2026-08-06 PM, user-approved)

The first live agent session exposed that support / exchange / search / subscription / usage rode
the `org:read` umbrella — REST's own coarseness, faithfully mirrored per §1 — so the filtered
`tools/list` had nothing finer to filter on and a minted-down key still saw (and could call) all
five areas. Approved the same day: seven area codes — `ticket:read`, `ticket:write`,
`exchange:read`, `exchange:submit`, `search:query`, `subscription:read`, `usage:read` — added to
`Permission`, and BOTH surfaces re-gated together (REST `@PreAuthorize` + the tool manifests: one
vocabulary, §1). Migration `V48` backfills every role holding `ORG_READ`, so no human loses access
(OWNER additionally self-heals via `RoleSeeder` reconciliation). API keys are deliberately NOT
backfilled — a key's grant is exactly the set a human minted, the property the escalation guard's
machine held-set rule stands on — so existing keys narrow on upgrade and are re-minted to opt in.
The catalog table (§4) shows the as-built codes. Pinned by the extended
`McpServerIntegrationTest.toolsListShowsOnlyWhatTheKeysPermissionsAllow` (an `org:read`-only key
sees none of the five areas) and the re-seeded module suites.

---

## 7. Testing & documentation duties

**Testing.** House rules apply unchanged (ADR 0003): every gate above runs on real containers.
The new piece is the **full-loop harness**: `@SpringBootTest(RANDOM_PORT)` (the third such class) +
the SDK's streamable-HTTP *client* driving the real endpoint — protocol, auth filter, dispatcher and
ports exercised as one path. Keys are minted through `ApiKeyService` (real hashing), not fixtures.
Contract-shaped classes: `McpAuthContractTest` (401/permission-filtering/fail-closed),
`McpWriteGuardTest` (maintenance + paused), `McpToolCatalogTest` (every registered tool declares a
permission that exists in `Permission`, complete metadata — version ≥ 1, a tag, a `kind` — and a
handler that resolves to a port; DESTRUCTIVE tools must carry the destructive annotation), plus
per-phase functional tests. MockMvc is not applicable — the servlet transport needs the real port.

**Documentation duties per phase** (AGENTS §13/§14 — same-slice, no exceptions):

| Doc | When |
|---|---|
| `docs/adr/0009-mcp-server.md` — protocol surface, stateless transport, API-key auth, port pattern | Phase 0 |
| `docs/guides/api-guide.html` — new Part **F · Agents (MCP)**: connecting, auth, tool catalog table per section; grows every phase | Phase 0, then every phase |
| `docs/guides/system-diagram.html` — `/mcp` path + `edge-mcp` policy in the gateway stage chain; `mcp` module in the module map | Phase 0 |
| `docs/SRS.md` — `/mcp` in the endpoint catalogue §4.6 + SHALL rows (permission-filtered discovery, fail-closed calls, write-guard) with verifying tests; `docs/LOCAL_ACCESS.md` connect-an-agent section | Phase 0, extended per phase |
| `docs/ARCHITECTURE.md` + `./gradlew exportModulithDocs` (new module + new ports change the canvas) | Phase 0, refreshed per phase |
| `docs/README.md` index (this plan), `docs/CHECKLIST.md` gate ledger | Phase 0, ticked per phase |
| `docs/EVENTS.md`, `docs/DATA_MODEL.md`, OpenAPI export | **No change** — no events, no tables, no MVC endpoints (stated here so their absence in reviews is read as deliberate) |

---

## 8. Non-goals (v1) — decisions, not deferrals

- **No API-key management tools.** An agent must not rotate or mint the credential it authenticates
  with — self-modifying auth is how a compromised key becomes a persistent one.
- **No platform-operator tools** (impersonation, cross-tenant admin, org suspend). The operator axis
  stays human + REST; if it ever becomes agent-facing it arrives with its own plan and its own ADR.
- **No role CRUD via MCP** (`roles_list` only). Editing permission bundles is a governance act;
  the blast radius of an agent mis-edit exceeds its utility. Member↔role assignment *is* exposed,
  guarded exactly like REST.
- **No analytics SQL tool.** `AnalyticsEngine`'s contract is developer-authored SQL only — an
  LLM-generated-SQL tool would break it by design. Canned, parameterized analytics tools may join a
  later phase as ordinary read tools.
- **No payment/billing writes.** Money movement stays in KillBill/PesaPal flows with human consent.
- **No binary transfer through MCP.** Documents and exchange results move via short-lived URLs.

## 9. Risks & mitigations

| Risk | Mitigation |
|---|---|
| SDK 2.0.0 is two months old; class names verified against docs, not compiled yet | Phase 0 starts with a spike commit that boots the servlet and runs the harness before any catalog work; the SDK is pinned in the catalog and trivially bumpable |
| Prompt injection via tool results (agent reads attacker-controlled webhook error bodies, document names, …) | Results are data by contract: curated fields only, `detail`-style strings, no free-text echo of stored payloads beyond what REST already returns; the api-guide Part F states the trust model for agent authors |
| A leaked `sk_` key now drives tools, not just REST | Same blast radius as today, same controls: permission subset capping, revocation (immediate — verified in the Phase 0 gate), per-org IP allowlist now enforced on `/mcp` too, rate limits at edge + app |
| Long-running tools vs. the 60 s edge timeout | Catalog rule: tools are interactive reads/enqueues; anything slow is submit-then-poll (`exchange_*` is the model). The write guard and dispatcher add no I/O beyond one policy read |
| Two-Jackson classpath confusion | The SDK bundle is Jackson 3 like the app; Jackson 2 remains only where it already was (springdoc, three tests). No new Jackson 2 entry points |
| Catalog drift from REST permissions | `McpToolCatalogTest` pins tool→permission pairs; the review checklist gains one line: an endpoint permission change updates the twin tool |

---

*Approved ⇒ Phase 0 starts. Tick gates in `docs/CHECKLIST.md`; the catalog tables above are the
authoritative tool inventory until superseded.*
