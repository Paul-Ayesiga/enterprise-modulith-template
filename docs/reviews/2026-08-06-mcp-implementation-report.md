# MCP implementation report — 2026-08-06

What shipped overnight: the full MCP (Model Context Protocol) agent surface planned in
[`docs/plans/MCP_PLAN.md`](../plans/MCP_PLAN.md) — all phases 0–6 (OAuth, phase 7, stays flagged
and unbuilt by design). **Nothing is committed**; the whole change-set sits in the working tree for
your review.

## The one-paragraph version

The platform now serves MCP at `/mcp`: **35 permission-gated tools across 8 modules**, 2 prompts,
3 drift-free reference resources — over the official MCP Java SDK 2.0.0's *stateless* streamable
HTTP servlet, authenticated by the existing org API keys (now also accepted as
`Authorization: Bearer sk_…` at both the gateway and the modulith), org-implicit, discovered
filtered, re-checked fail-closed per call, IP-allowlist- and write-guard-enforced, audited and
metered. Tool handlers are one-line delegations to **13 new module ports** whose impls call the
same internal services REST calls — every existing invariant (escalation guard, SSRF guard,
entitlement caps, last-owner lock) fires identically for agents. 30 new MCP-focused tests run the
real SDK client against the booted app on real Postgres; the full root suite and the gateway suite
are green.

## Where to look first

| Artifact | Path |
|---|---|
| The plan (approved, amended with your foundation asks) | `docs/plans/MCP_PLAN.md` |
| Decision record | `docs/adr/0009-mcp-server.md` |
| **The guide — diagrams + full detail** | `docs/guides/mcp-guide.html` (open in a browser) |
| REST-docs twin (Part F) | `docs/guides/api-guide.html` |
| Architecture diagram updates | `docs/guides/system-diagram.html` |
| Dogfood config (Claude Code) | `.mcp.json` (repo root; export `SMSONE_API_KEY`; points at the gateway `:28090`) |

## What was built, phase by phase

**Phase 0 — foundation.** `ug.co.smsone.mcp` module: stateless servlet at `/mcp`
(`McpEndpointConfig`), `ToolContext` frozen per-request on the servlet thread and carried through
the SDK's transport context, `ToolDefinition` (name/title/description/**version**/**area
tag**/**READ‑WRITE‑DESTRUCTIVE kind**/permission/schema/handler), `ToolManifest` per module area
(your manifest ask — kept inside the mcp module so the dependency arrow stays `mcp → module API`),
`McpToolRegistry` (fail-fast catalog validation at boot), `McpAccessPolicy` (one visibility+call
answer via the REST evaluator), `McpToolDispatcher` (the single pipeline: permission → IP allowlist
→ write guard → port call → audit + metrics + requestId; installs/restores the caller's
SecurityContext around the handler, `finally`-guarded), `McpWriteGuard` + two new read ports
(`maintenance.MaintenanceState`, `subscription.SubscriptionGate`), `McpCatalogFilteringTransport`
(permission-filtered `tools/list` + the `app.mcp.enabled` refuse-not-vanish kill switch),
`McpErrorMapper` semantics inside the dispatcher (typed `ApiException` → `{code, detail,
requestId}`, unexpected → one generic sentence + ERROR log). Auth: `ApiKeyAuthenticationFilter`
accepts `Bearer sk_…`; a custom `BearerTokenResolver` keeps the JWT machinery's hands off key
bearers (and the reactive equivalents at the gateway). `RateLimitFilter` matcher widened to `/mcp`
with a per-key `mcp` tier. Gateway: `mcp-api` route + `edge-mcp` policy (authenticated,
rate-limited, 60 s, 2 MiB), pinned by `ShippedRouteTableTest`. Micrometer counters/timers.
`whoami`.

**Phase 1 — organization + subscription + billing (9 tools).** New ports `OrgDirectory`,
`OrgMembers`, `OrgRoles`, `SubscriptionOverview`, `UsageSummaries`. Member writes ride
`MemberService`, so provisioning order, idempotent re-invite, the members-max cap and the
last-owner lock all hold.

**Phase 2 — webhooks (8 tools).** New port `WebhookAdmin`; full manage + delivery log + **new
redelivery capability**: a fenced `requeueFailed` in `WebhookDeliveryQueue` (only FAILED rows;
attempts reset; the JPA read-model stays read-only by design) surfaced as
`WebhookSubscriptionService.redeliver(...)` with its own audit action.

**Phase 3 — support + documents (9 tools).** New ports `SupportDesk` (tenant view only — internal
notes never cross) and `DocumentDirectory` (metadata, presigned download, bytes-now/row-soft
delete). Document *upload* stays REST multipart — binary transfer through MCP is a §8 non-goal.

**Phase 4 — exchange + search (8 tools).** New ports `ExchangeJobs` (handlers/submit/poll/cancel/
artifact URLs — the async pattern) and `SearchQueries` (org-scoped only). Exports enforce each
handler's own export permission at submit.

**Phases 5–6 — agent content.** Prompts `diagnose_failed_webhooks` and `usage_review` (worked
procedures, not documentation); resources `smsone://guide/agent` (bundled),
`smsone://reference/permissions` and `smsone://reference/webhook-events` (rendered from the enums
on every read — they cannot drift). `.mcp.json` at the repo root.

## Decisions you should review (the judgment calls)

1. **Machine held-set for grants** — `PermissionEscalationGuard` and `ExchangeService`'s
   permission check previously resolved the caller's permissions by *membership lookup of the
   subject*. An API key (`key:<id>`) is never a member, so **every key-driven invite/re-role/export
   already 403'd on REST too**. Both now have a machine branch: a key's minted subset is its held
   set (capped at mint by a human who held those permissions — the no-escalation invariant carries
   transitively). This deliberately *enables* key-driven member management on REST as well;
   `McpMemberWriteToolsTest` pins both directions (within-subset works, beyond-subset refuses
   before any side effect).
2. **Webhook redelivery is new capability**, not just a port — and it now has full REST parity:
   `POST /api/v1/orgs/{orgId}/webhooks/{id}/deliveries/{deliveryId}/redeliver` (202; 409 unless
   FAILED), added on your morning instruction with its own `WebhookApiTest` coverage. Fenced on
   FAILED so a concurrent redeliver or worker can't double-apply.
3. **Resources deviate from the original plan text** (OpenAPI/api-guide as MCP resources) in favor
   of three zero-drift resources; the plan §Phase 6 was updated with the rationale (a resource that
   mirrors a repo file is the classic way reference docs rot; the enums can't lie).
4. **`tools/list` filtering** required decorating the SDK's protocol handler (the SDK has no
   per-request list hook) — `McpCatalogFilteringTransport` wraps `setMcpHandler`; the visible
   catalog is a courtesy, the per-call re-check is the boundary.
5. **`Bearer sk_` at both tiers** — without the resolver/converter guards, Spring's JWT machinery
   401s the very request the key filter just authenticated (found by the failing bearer test, fixed
   at gateway + modulith symmetrically).

## Found in live testing — two transport bugs (fixed same day)

Connecting Claude Desktop (via the `mcp-remote` stdio bridge) surfaced two latent platform bugs that
curl-based testing could never see. Both are fixed and regression-pinned; both predate MCP — the MCP
surface was merely the first caller strict enough to notice.

1. **The gateway killed keep-alive connections after every quota-checked response.** `QuotaFilter`
   ran `chain.filter(...)` — a `Mono<Void>`, which always completes *empty* — upstream of a
   `.switchIfEmpty(...)` fallback, so any request whose consumer resolved a quota re-subscribed the
   whole filter chain onto the already-committed response. The second pass re-fired the route's
   Redis rate limiter, whose late header write threw `UnsupportedOperationException` against the
   committed response, and reactor-netty closed the connection without clean framing. curl tolerates
   that; undici (the HTTP client inside `mcp-remote`, and Node's `fetch`) fails the request with
   `other side closed` — which Claude Desktop renders as "Server disconnected". Fix: decide
   admission first, then run the chain exactly once (`QuotaFilter`), the same shape
   `EdgeAuthorizationFilter` already uses for exactly this reason. Pinned by
   `QuotaFilterChainTest` (chain subscription counts per admission outcome).
2. **`GET /mcp` with a valid key answered 401 instead of 405.** The SDK's stateless servlet
   correctly `sendError(405)`s the stream-open GET (no server push without sessions), but Boot's
   ERROR dispatch to `/error` re-entered the security chain anonymously (the key filter skips error
   dispatches) and the entry point rewrote the 405 into 401 — telling remote MCP clients their valid
   key was bad right after a successful `initialize`. Fix: permit `DispatcherType.ERROR` in
   `SecurityConfig` (what Boot's own default chain does; a direct request to `/error` is a REQUEST
   dispatch and stays authenticated). Pinned in `McpServerIntegrationTest`
   (`aGetWithAValidKeyIsA405NotA401`, including the anonymous-GET-stays-401 half).

Also closed in passing: the gateway's `products` map now has the `agents` entry the `mcp-api` route
references, so the catalog/portal group renders with a name instead of a bare key.

## The afternoon's polish — a real permission vocabulary for the five areas

The first live agent session showed a 10-permission key a 35-tool catalog. That was not the filter
failing — it was the platform's vocabulary being coarser than its surface: support, exchange,
search, subscription and usage had no codes of their own and rode `org:read` on REST, so MCP's
mirror had nothing finer to check. Approved same-day (with the key-narrowing option chosen):

- **Seven new codes** in `Permission`: `ticket:read`, `ticket:write`, `exchange:read`,
  `exchange:submit`, `search:query`, `subscription:read`, `usage:read` — and BOTH surfaces re-gated
  together (five REST controllers' `@PreAuthorize` + four tool manifests), keeping the one-vocabulary
  invariant.
- **`V48`** backfills every role holding `ORG_READ` with all seven, so no human loses any access
  this re-gate touched (OWNER also self-heals via `RoleSeeder`'s drift reconciliation). **API keys
  are deliberately not backfilled**: a key's grant is exactly what a human minted — the escalation
  guard's machine held-set rule depends on that — so existing keys narrow until re-minted.
  Release-note this on real deployments; handler-level export permissions are unchanged and still
  enforce at submit.
- **The filtered `tools/list` now has teeth**: an `org:read`-only key sees none of the five areas —
  pinned as an assertion in `McpServerIntegrationTest`, alongside re-seeded suites across apikeys,
  access, exchange, search, subscription, support and mcp (149 tests green).
- Docs synced: SRS §4.6 permission column + definitions row, DATA_MODEL's catalog paragraph,
  api-guide endpoint chips + Part F table, mcp-guide catalog tables, MCP_PLAN §4 + new Phase 8.

## The evening's close — Phase 7, OAuth 2.1 for native connectors

The last flagged phase shipped the same day, so the plan has no deferred phases left. Claude
Desktop / claude.ai custom connectors now connect with just the URL: the anonymous 401 carries the
RFC 9728 challenge, the metadata document (served by **Spring Security 7's own
`OAuth2ProtectedResourceMetadataFilter`** — discovered mid-build when it answered before a custom
servlet, which was then deleted; a `SecurityConfig` customizer pins the external resource id,
Keycloak realm and `mcp` scope) names the authorization server, the connector self-registers under
POLICED anonymous registration (trusted hosts: loopback + the pinned compose-network gateway — docker port-mapping means registrations never arrive from localhost; consent forced; `mcp` explicitly allowlisted —
mirrored into `docker/keycloak/realm-smsone.json` after learning the exact component shapes from a
live Keycloak's own defaults), and the browser consent mints a token whose `mcp` scope stamps BOTH
audiences (`smsone-api` for the global resource-server check, `smsone-mcp` for the `/mcp` door).

The door policy (`McpTokenAuthorizationManager`) admits exactly two credentials at `/mcp`: `sk_`
keys, or JWTs carrying `smsone-mcp` — the SAME user's web login token is refused there, killing
cross-surface token reuse in both directions (user-approved decision, alongside keys-narrow and
policed-DCR). OAuth callers resolve like REST humans (subject, tenant-claim org, membership
permissions), so the filter, dispatcher re-check, guards and audit all work unchanged; `whoami`
answers `authKind: "oauth"`.

Verified: `McpOAuthIntegrationTest` against a real Keycloak importing the committed realm (both
door directions + policed DCR + dual-audience claim), the discovery/challenge pins in
`McpServerIntegrationTest`, the gateway metadata route pin, and full root + gateway suites. The
live dev Keycloak was brought up to date non-destructively via the admin API (scope, optional
wiring, trusted hosts) — no `make nuke` required.

## Verification

- **30 MCP tests** across 8 classes — all through the real SDK client over real HTTP against real
  Postgres, keys minted with the production hash: `McpServerIntegrationTest` (protocol loop, both
  auth spellings, 401s, hidden-tool-denies), `McpGuardrailsTest` (maintenance/paused/IP-allowlist,
  denial-before-side-effect proven by a counting tool), `McpToolCatalogTest` (metadata + truthful
  wire annotations), `McpAccountToolsIntegrationTest`, `McpMemberWriteToolsTest` (escalation guard
  from the org package with the Keycloak gateway stubbed), `McpWebhookToolsIntegrationTest`
  (lifecycle + fenced redeliver + SSRF/unknown-event refusals + log-survives-delete),
  `McpSupportDocumentToolsIntegrationTest`, `McpExchangeSearchToolsIntegrationTest` (async flow,
  handler-permission refusal, tenant isolation of search).
- **Full root suite + gateway suite: green** (run at the end of the session; the only pre-existing
  churn is the regenerated `docs/openapi/*` and `docs/modulith/*`).
- `ModularityTests` / `ArchitectureTests` pass — the new module and all 13 ports respect every
  boundary rule.

## Docs updated (AGENTS §13 duties)

`docs/plans/MCP_PLAN.md` (amended + catalog synced to as-built), `docs/adr/0009-mcp-server.md`,
`docs/guides/mcp-guide.html` (new), `docs/guides/api-guide.html` Part F,
`docs/guides/system-diagram.html`, `docs/ARCHITECTURE.md`, `docs/SRS.md` (§4.6 + SHALLs),
`docs/LOCAL_ACCESS.md`, `docs/README.md`, `docs/CHECKLIST.md`, regenerated `docs/modulith/` and
`docs/openapi/` (the latter unchanged in substance — MCP is not an MVC surface). `.mcp.json` new.

## Not done, on purpose

OAuth 2.1 for claude.ai custom connectors (plan phase 7, flagged); API-key/role/analytics-SQL/
payment tools and platform-operator tools (plan §8 non-goals); MCP import submission (multipart
stays REST).
