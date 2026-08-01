# Gateway Build Plan

How we build the gateway **incrementally** — useful from the first slice, growing into a full edge
platform. Design is in [GATEWAY_ARCHITECTURE.md](GATEWAY_ARCHITECTURE.md); the decision is
[adr/0007-api-gateway.md](adr/0007-api-gateway.md). This is a **plan** — no gateway code exists yet;
it is written to be approved before Phase 1 starts (the repo's plan-first rule).

## Sequencing rationale

Each phase leaves a **runnable, useful** gateway; nothing is a big-bang. The order is dependency-first:
you cannot rate-limit a route you cannot yet route, and you cannot observe a pipeline that does not yet
exist. So Core → Security → Traffic → Observability, then the platform-shaping layers (Extensibility,
Admin) and finally Enterprise management. Ship a phase, gate it on real containers, then start the next
— the same rhythm the modulith's expansion slices used.

Capabilities from the brief map onto the phases like this:

| Phase | Capabilities it lands |
|---|---|
| 1 · Core Gateway | routing, reverse proxy, service registry, static discovery, health checks, request-id, config model, the pipeline skeleton |
| 2 · Security | JWT/OIDC (Keycloak), API keys, internal tokens, coarse authZ, tenant resolution, CORS, security headers, TLS termination |
| 3 · Traffic | rate limiting, throttling, request/response validation, retries, timeouts, circuit breaking, load-balancing strategies, caching, compression |
| 4 · Observability | structured logging (separated), metrics (Prom/OTel), distributed tracing, audit sink, error model |
| 5 · Extensibility | plugin framework, policy engine, request/response transformation, protocol niceties (WebSocket/SSE), dynamic routes |
| 6 · Administration | admin APIs, consumer registry, API keys management, quotas, config reload, service discovery integrations |
| 7 · Enterprise | API products, developer portal, API lifecycle, versioning, analytics, multi-tenant branding, multi-region/failover |

---

## Phase 1 — Core Gateway (the first slice)

**Status: shipped 2026-08-02.** Delivered the `gateway:core` + `gateway:app` subprojects; the
config-driven route/service model with the `RouteSource`/`ServiceRegistry` ports + static-YAML
adapter; reverse proxy to the modulith; request-id + access-log filters; the `NO_ROUTE` error
envelope; the hexagon boundary test — gated by `RoutingTest` (all 5 predicate kinds, a 404 envelope,
request-id mint/honor/propagate) on **Spring Cloud Gateway 5.0.2 / Boot 4.1** (compatibility
verified). Scope notes vs. the original sketch: the `security`/`platform-adapter`/`admin`/`starter`
subprojects are created with their phases rather than stubbed empty now; backend health-gating moves
to Phase 3 (it needs the load balancer); the gateway runs via `bootRun` (the modulith is not
containerized, so there is no compose service yet — gateway/README.md).

**Focus.** A reactive Spring Cloud Gateway deployable that routes to the modulith by configuration,
reports health, and stamps a correlation id — with the hexagonal skeleton (ports defined, pipeline
ordered) in place so later phases slot in without refactoring.

**Deliverables**

- **Multi-project build.** `settings.gradle.kts` includes `gateway/core`, `gateway/security`,
  `gateway/platform-adapter`, `gateway/admin`, `gateway/starter`, `gateway/app`. The modulith build is
  untouched. `gateway-core` has a structure test forbidding imports of any `ug.co.smsone.<platform>`
  package (mirrors the modulith's `ModularityTests`).
- **Stack.** Spring Cloud Gateway (reactive/WebFlux) + reactive Redis (Lettuce), pinned to the Spring
  Cloud release train that matches Boot 4.1 (see Risks). `gateway-app` is the only bootable module.
- **Route + Service models** as configuration (YAML first): predicates (path/host/header/method/query),
  `priority`, target service, timeout. `RouteSource` and `ServiceRegistry` ports with a
  static-YAML adapter; the platform adapter comes later.
- **Reverse proxy** to the modulith (`/api/v1/**` → modulith service), hiding its address; static
  discovery now, pluggable (`ServiceResolver` port) for K8s/Consul/Eureka later.
- **Health** — the gateway's own liveness/readiness, plus backend health checks that gate routing.
- **Request pipeline skeleton** in the documented order (Architecture §7) with request-id/correlation
  (reusing the modulith's `X-Request-Id` convention) and access logging live; later stages present as
  ordered no-ops so ordering never has to be reworked.
- **All gateway-core ports defined** (`ConsumerResolver`, `QuotaProvider`, `AuditSink`,
  `TenantResolver`, `AuthNProvider`, `AuthZProvider`, `RouteSource`, `ServiceRegistry`) with no-op/deny
  defaults, so the core compiles and runs before any adapter exists.

**Gate.** `./gradlew :gateway:app:test` green: a request routes to a **WireMock** backend by each
predicate type; an unknown path is a clean gateway `404`; the request-id is generated when absent and
propagated when present; health flips a route out when the backend is down. Compose brings up the
gateway in front of the real modulith and a smoke script drives `/api/v1/**` end to end.

---

## Phase 2 — Security

**Status: 2a shipped 2026-08-02 (JWT/OIDC + coarse authZ + CORS + headers); 2b next (API-key
introspection).** Delivered the `gateway:security` subproject — a reactive OAuth2 resource server that
validates a bearer JWT against the IdP's JWKS (no platform call; invalid/expired/tampered → 401 in the
gateway envelope), and an `EdgeAuthorizationFilter` that applies each route's coarse `AuthPolicy`
(authenticated required, every required scope present, tenant-in-path == token tenant), stamps
`X-Auth-Subject`/`X-Tenant-Id` downstream, and forwards the bearer (services keep their fine-grained
checks — ADR 0007 §8). CORS is centralized at the edge and security headers are added. `SecurityTest`
(11 tests, controlled in-test JWKS) covers valid/invalid/expired/tampered tokens, missing-scope and
wrong-tenant 403s, CORS preflight, and headers. **2b** (the remaining deliverables below): API-key +
internal-token `AuthNProvider`s via the modulith key-introspection endpoint — the first platform adapter.

**Focus.** The gateway becomes the platform's edge security layer.

**Deliverables** — JWT/OIDC validation against **Keycloak JWKS** (cached, no platform call);
`AuthNProvider` implementations for API keys (via the introspection adapter) and internal service
tokens; **coarse** authorization (tenant match + scope/role per route policy); tenant resolution
(header / subdomain / JWT claim / API key / path); CORS centralized (services stop doing it); security
headers; TLS termination (or pass-through) with cert management hooks. The modulith gains a thin
**key-introspection** endpoint backing the API-key adapter.

**Gate.** A valid Keycloak token passes and its tenant/scope are enforced; an invalid/expired token is
`401` before any backend call; a wrong-tenant request is `403`; an API key resolves through
introspection; CORS preflight is answered at the edge. Keycloak Testcontainer + WireMock backend.

---

## Phase 3 — Traffic management

**Focus.** Protect backends and shape load.

**Deliverables** — rate limiting on **reactive Valkey** (fixed/sliding window, token bucket) keyed by
IP / consumer / tenant / API key / route; throttling (slow, don't reject) for spikes; request
validation (required headers, max body size, content-type, allowed methods) at the edge; resilience
policies per route — retries (idempotent methods only, honoring ADR 0005 semantics), timeouts, circuit
breakers, bulkheads, fallbacks; load-balancing strategies (round-robin / least-conn / weighted /
consistent-hash); response caching for safe GETs; selective compression (gzip/brotli).

**Gate.** A consumer over its limit gets `429` with `Retry-After`; a tripped circuit fails fast with
`503` and recovers; a slow backend times out at `504`, not a hung thread; an oversized body is `413`.
Valkey Testcontainer; a chaos test kills the backend mid-flight and the circuit opens.

---

## Phase 4 — Observability

**Focus.** Every request is measured, traced, and logged — separably.

**Deliverables** — structured logs split into access / security / audit / gateway / system (never
mixed); metrics to Prometheus/OpenTelemetry (RPS, error rate, p50/p95/p99, active connections,
per-route utilization, authN/authZ failures, backend failures); distributed tracing propagating
`traceId`/`spanId` across backends; the **`AuditSink` adapter** publishing edge audit events to the
platform's `AuditLog`; the one gateway error envelope (Architecture §11).

**Gate.** One trace id spans gateway → modulith in the collector; the four log streams are separable;
`/actuator/prometheus` (admin port) exposes the metric set; an auth failure lands in the security log
and an edge audit event reaches the platform.

---

## Phase 5 — Extensibility

**Focus.** Turn cross-cutting behavior into a first-class plugin/policy system.

**Deliverables** — a **plugin framework** (every §7 stage is a registered plugin, independently
configurable); a **policy engine** (compose auth/rate-limit/transform/cache/retry policies and attach
them to routes/consumers/tenants without code); request transformation (path/URL rewrite, header/query
manipulation, metadata enrichment, `X-Tenant`/`X-Consumer` injection, `Authorization` stripping);
response transformation (strip internal headers, mask fields, security/cache headers); WebSocket/SSE
pass-through; **dynamic routes** (add/change a route without a redeploy).

**Gate.** A custom plugin loads and runs in the pipeline; a policy attaches to a route and changes
behavior with no code change; a WebSocket proxies end to end; a route added via config takes effect
without restart.

---

## Phase 6 — Administration

**Focus.** Operate the gateway; make consumers first-class.

**Deliverables** — admin REST APIs (routes, services, policies, plugins, rate limits, consumers, API
keys, health, metrics) on a **separate port/network** from public traffic; the **consumer registry**
(the `ConsumerResolver` backed by the api-keys module + Keycloak clients); API-key lifecycle
(generate/rotate/expire/revoke) delegating to the apikeys module; quotas (`QuotaProvider` backed by
subscriptions/entitlements — monthly/daily/hourly); config reload; service-discovery integrations
(K8s/Consul/Eureka/Nacos/DNS) behind `ServiceResolver`.

**Gate.** An operator lists/creates a route via the admin API (public traffic never sees it); a
consumer's quota is enforced from the subscription plan; a rotated key works and the old one is
revoked; a discovery source registers a backend automatically.

---

## Phase 7 — Enterprise enhancements

**Focus.** Grow from gateway into API management.

**Deliverables** — API products (group APIs for onboarding/monetization); OpenAPI import/export/publish
(the modulith already generates its spec); API lifecycle (draft → testing → published → deprecated →
retired); versioning (URI / header / media-type, per route); developer portal (catalog, docs,
interactive try-out, SDKs, subscription self-service); advanced analytics; multi-tenant policies &
branding; multi-region failover.

**Gate.** Defined per capability when the phase is scoped — this phase is a backlog, prioritized when
Phases 1–6 are in production.

---

## Repo integration

- **Build.** Convert to a Gradle multi-project: root `settings.gradle.kts` gains
  `include("gateway:core", "gateway:security", "gateway:platform-adapter", "gateway:admin",
  "gateway:starter", "gateway:app")`. The existing modulith becomes (or stays) the root/`:app`
  project — **its build and tests do not change**. Version catalog (`gradle/libs.versions.toml`) gains
  the Spring Cloud BOM + gateway/webflux/lettuce-reactive entries.
- **Runtime.** The gateway runs via `./gradlew :gateway:app:bootRun` on `:8090`, fronting the modulith
  on `:8080` (the modulith runs on the host with infra from compose, so there is no containerized
  `gateway` service yet). When the modulith is containerized, add a `gateway` compose service sharing
  the Keycloak realm and Valkey; ports follow the `docker/.env` conventions.
- **Platform side.** A small, versioned **gateway-integration API** on the modulith (key introspection,
  quota lookup, audit ingest), each endpoint backed by an existing port (`ApiKeyAuthenticator`,
  `Entitlements`, `AuditLog`) and covered by a contract test.
- **Docs duties.** New edge concerns update this plan and the architecture doc; a gateway change never
  silently diverges from either (AGENTS §13 discipline, extended to the gateway).

---

## Open decisions (confirm before Phase 1)

1. **Monorepo vs. separate repo.** Plan assumes **monorepo** (gateway subprojects here) for shared CI,
   atomic changes, and one version catalog. A separate repo trades that for independent release
   cadence and easier open-sourcing later. *Recommendation: monorepo now; split when it earns it.*
2. **Spring Cloud Gateway flavor.** **Reactive (WebFlux)** per the reactive-first principle. (Spring
   also ships a servlet `spring-cloud-gateway-server-webmvc`; it would share the modulith's stack but
   forfeit the reactive model — not chosen.)
3. **TLS termination point.** Gateway vs. an upstream LB/ingress. Plan supports both; pick per
   deployment.
4. **Introspection vs. token-only.** Prefer decisions from the JWT + Valkey; add an introspection call
   only where the edge genuinely cannot decide (each call is hot-path latency).

## Risks

- **Boot 4.1 ↔ Spring Cloud alignment.** Spring Cloud releases on a train tied to Boot versions; the
  gateway must pin the train that supports Boot 4.1. If it lags, either wait, or run the gateway on the
  matching Boot minor independently of the modulith (they are separate deployables — allowed).
- **Reactive discipline.** One blocking call on the hot path (a blocking client, a `block()`) silently
  destroys throughput. Enforce with BlockHound in tests.
- **Latency budget.** The gateway is on every request. Introspection/quota calls must be cached and
  bounded; set p99 budgets in the Phase-3 load gate and hold them.
- **Auth split drift.** If a service quietly starts trusting "the gateway already checked," defense in
  depth is gone. The coarse/fine split (Architecture §8) is a review-checklist item, not a suggestion.
