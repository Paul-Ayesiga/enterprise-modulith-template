# Gateway Architecture

The platform's programmable front door. This document is the north star — *what the gateway is and
the rules it obeys*. The phased build lives in [GATEWAY_PLAN.md](GATEWAY_PLAN.md); the decision record
is [adr/0007-api-gateway.md](../adr/0007-api-gateway.md).

> One line: a **stateless, reactive Spring Cloud Gateway**, built **hexagonally** — a generic core of
> edge concepts that depends only on **ports**, with the platform plugging in through **adapters**.

> **Status (2026-08-02): implemented — all 7 phases shipped.** The subprojects that shipped are
> `gateway:core`, `gateway:security`, `gateway:platform-adapter`, `gateway:app`; the `admin` and `starter`
> subprojects in §5 were folded into `app` (admin as management endpoints on a separate port; components
> scanned, no separate starter). This document remains the north star (*what the gateway is and the rules
> it obeys*); the per-phase shipped state is in [GATEWAY_PLAN.md](GATEWAY_PLAN.md).

---

## 1. Objective and non-goals

**Objective.** Be the single entry point into the platform and centralize every *edge* concern —
routing, security, traffic policy, request/response processing, observability, API governance — so no
downstream service reimplements them.

**The gateway MUST NOT** (these are the guardrails that keep it infrastructure, not an application):

- access a business database, or open a JDBC/JPA connection to one;
- execute a domain workflow, persist a business entity, or contain a domain service;
- implement a business rule, or *replace* a service's own authorization;
- hold per-request tenant state, an HTTP session, or a local cache of tenant data.

**The gateway SHALL only** implement edge concerns, and remain stateless, configurable, and
horizontally scalable. State lives in the JWT, in Valkey (only when necessary), in external
configuration, or at the identity provider — never in a gateway instance.

---

## 2. Architectural principles

1. **Separation of concerns.** Edge concerns here; business concerns behind. The line is bright: if a
   change needs to know what a *user*, an *invoice*, or an *organization* means, it belongs behind the
   gateway.
2. **Stateless design.** Every instance is interchangeable. No sticky sessions, no in-memory auth
   state, no local tenant caches. Scale is `replicas++`.
3. **Reactive first.** The gateway is WebFlux end to end — no blocking JDBC, `Thread.sleep`, blocking
   HTTP clients, or blocking filesystem calls. This is *why* it is a separate deployable from the
   servlet + blocking-JDBC modulith (see §6).
4. **Hexagonal (ports & adapters).** The core owns edge *concepts* and depends only on interfaces;
   the platform supplies *implementations*. Dependency points inward — the core never imports a
   platform module. This is the Spring Security `UserDetailsService` / Hibernate `Dialect` philosophy
   (§4).
5. **Coarse at the edge, fine in the service.** The gateway answers "authenticated? right tenant? has
   the required scope/role?" Services keep `hasPermission` (defense in depth). The gateway is a gate,
   never *the only* gate (§8).
6. **Configuration over code.** Routes, services, and policies are data, not classes. A new route is a
   config change, not a deploy.
7. **Everything is a filter/plugin.** Cross-cutting behavior is a link in one ordered pipeline
   (§7) — added, removed, and reordered by configuration, never by editing the core.

---

## 3. Where it sits

```
                         Internet
                            │
                      Load Balancer         (TLS terminates here or at the gateway)
                            │
                 ┌──────────────────────┐
                 │     API Gateway       │   stateless · reactive · N replicas
                 │  routing · security   │
                 │  policies · telemetry │
                 └──────────────────────┘
             coarse authZ │ rate limit │ transform
                            │
        ┌───────────────────┼─────────────────────┐
        │                   │                     │
   Modulith (:8080)   (future) Finance svc   (future) Notify svc
   servlet + JDBC          reactive/…            reactive/…
        │
        └── keeps its OWN fine-grained authorization (hasPermission)
```

Today the gateway fronts one backend — the modulith. Its whole reason to exist is that "one backend"
becomes "many" over time; the routing/service model (§7) is built for that from day one.

---

## 4. Dependency direction — the hexagon

The core is generic. It knows *that* it needs a consumer, a quota, an audit sink — never *how* the
platform provides them.

```
                    ┌───────────────────────────────┐
                    │          gateway-core          │
                    │  Routes · Services · Consumers │
                    │  Plugins · Policies · Filters  │
                    │        Request pipeline        │
                    └───────────────────────────────┘
                          defines ▲ (ports, inward)
        ┌───────────────┬─────────┴───────┬──────────────────┐
   ConsumerResolver  QuotaProvider     AuditSink       TenantResolver
   AuthNProvider     AuthZProvider     RouteSource     ServiceRegistry
        ▲               ▲                ▲                  ▲
        └───────────────┴──── implemented by ──────────────┘
                    ┌───────────────────────────────┐
                    │      gateway-platform-adapter  │  (this template's plug-in)
                    │  api-keys · subscriptions ·    │
                    │  audit · webhooks · Keycloak · │
                    │  Valkey  — over the network    │
                    └───────────────────────────────┘
```

The arrow that matters: **the gateway does not depend on the platform; the platform's adapter depends
on the gateway's ports.** Swap `gateway-platform-adapter` for a `redis-adapter`, an `aws-adapter`, or
a `static-config-adapter` and the core is unchanged. That is what makes the core reusable and, later,
open-sourceable.

---

## 5. Subproject layout

A Gradle multi-project under `gateway/`. Each has one responsibility; `core` has **no** platform
dependency.

| Subproject | Contains | Depends on |
|---|---|---|
| `gateway-core` | Route/Service/Consumer models, plugin & policy engine, the request pipeline, **all ports**, the gateway `RequestContext` and error model | nothing platform-specific |
| `gateway-security` | JWT/OIDC validation (Keycloak JWKS), API-key & internal-token auth providers, coarse authZ, CORS, security headers, tenant resolution | `gateway-core` |
| `gateway-platform-adapter` | This template's adapters: consumers ← api-keys, quotas ← subscriptions/entitlements, audit ← `AuditLog`, events ← webhooks; over HTTP/Valkey | `gateway-core` + platform HTTP contracts |
| `gateway-admin` | Management REST APIs (routes, services, policies, consumers, health, metrics) on a **separate port/network** from public traffic | `gateway-core` |
| `gateway-starter` | Spring Boot auto-configuration that wires a chosen set of adapters + defaults | the above |
| `gateway-app` | The thin bootable jar: applies the starter, ships config | `gateway-starter` |

Package root `ug.co.smsone.gateway.*`; `gateway-core` imports **no** `ug.co.smsone.<platform-module>`
type — enforced the same way the modulith enforces module boundaries (a structure test, AGENTS §1).

---

## 6. The reactive integration seam

This is the subtle, load-bearing part. The modulith exposes clean **in-process** ports today —
`ApiKeyAuthenticator`, `OrgAuthorization`, `AuditLog`, `Entitlements`, `Subscriptions`. The gateway is
a **separate reactive process** and cannot call them in-process. So an adapter bridges each gateway
port to the platform **over the network**, and those network endpoints are in turn backed by the
existing ports:

```
 gateway-core port (reactive)
        │  e.g. ConsumerResolver.resolve(key): Mono<Consumer>
        ▼
 gateway-platform-adapter        ── reactive WebClient / reactive Valkey ──▶
        ▼
 platform gateway-integration API  (thin, new, on the modulith)
        │  in-process
        ▼
 existing ports: ApiKeyAuthenticator · OrgAuthorization · AuditLog · Entitlements
```

Consequences of the seam, per concern:

| Gateway concern | How it resolves — reactive, no blocking | Platform work needed |
|---|---|---|
| **JWT / OIDC** | Gateway validates against **Keycloak JWKS** directly (cached keys). No platform call. | none |
| **API key** | Adapter calls a **key-introspection** endpoint (or reads a Valkey snapshot the apikeys module publishes). Backed by `ApiKeyAuthenticator`. | expose introspection |
| **Consumer identity** | From the JWT (subject, `organization` claim) and/or the key introspection. `CurrentUser` is the shape it maps to. | none / introspection |
| **Quota** | Adapter calls a **quota-lookup** endpoint or reads a published snapshot. Backed by `Entitlements`/`Subscriptions`. | expose quota lookup |
| **Coarse authZ** | From JWT roles/scopes + tenant match. No platform call. | none |
| **Audit** | Adapter publishes to an **audit sink** (HTTP or a queue) → `AuditLog.record`. | expose audit ingest |
| **Rate-limit buckets** | **Reactive Valkey**, keyed per consumer/tenant/route (§7). Shared with the modulith's Valkey. | none |

Design rule: **prefer the JWT and Valkey (no round trip); fall back to introspection only when the
edge genuinely cannot decide from the token.** Every introspection call is latency on the hot path.

---

## 7. The request pipeline

One ordered chain. Order is load-bearing — the modulith already learned this (its filter order is a
documented invariant, AGENTS §5.5); the gateway inherits the discipline.

```
Client ─▶ Correlation/Request-Id
       ─▶ Request logging (access log)
       ─▶ Tenant resolution        (header | subdomain | JWT claim | API key | path)
       ─▶ Authentication           (JWT/OIDC · API key · internal token · anonymous)
       ─▶ Authorization (COARSE)   (tenant match · scope/role · route policy)
       ─▶ Request validation       (headers · size · content-type · method)
       ─▶ Rate limiting            (per consumer/tenant/API-key/route)
       ─▶ Request transformation   (rewrite path · inject X-Tenant/X-Consumer · strip Authorization)
       ─▶ Routing → Load balance → Resilience (retry · timeout · circuit breaker)
       ─▶ [ backend ]
       ─▶ Response transformation  (strip internal headers · security headers · compression)
       ─▶ Metrics + tracing span close
       ─▶ Response logging
Client ◀─
```

Each stage is a plugin bound to a route/service/consumer by policy (§9), never hardcoded. A route may
opt a stage in or out (a public health route skips auth; an internal route requires mTLS).

---

## 8. Coarse edge authZ vs fine service authZ

The single most important boundary to get right.

- **The gateway (coarse):** is the caller authenticated? Does the token's tenant match the route's
  tenant? Does it carry the scope/role the *route* requires? These are cheap, token-only, and generic
  — the gateway needs no knowledge of the org permission model.
- **The service (fine):** `hasPermission(subject, orgId, 'invoice:refund')`. This stays exactly where
  it is today (`OrgAuthorization`, `ApiPermissionEvaluator`). The gateway passing a request through is
  **never** a service's cue to skip its own check.

Why not push fine authZ to the edge? It would couple the gateway to the permission catalogue, force
an introspection call per request, and create a single point where a mistake removes *all* enforcement.
Coarse-at-edge + fine-in-service is defense in depth and keeps the gateway generic.

---

## 9. Core models

Treated as **configuration**, versioned and hot-reloadable where practical.

**Route** — `id · name · description · status · priority · predicates (path/host/header/method/query)
· filters · target service · timeout · retry policy · auth policy · authZ policy · rate-limit policy ·
tags`.

**Service** — the backend, separate from the routes that point at it: `id · name · protocol · host ·
port · health endpoint · timeouts · load-balancing strategy · metadata`. Many routes → one service.

**Consumer** — the API client as a first-class entity, independent of *where* it comes from: `id ·
name · status · credentials · quotas · policies · metadata`. Whether a consumer is a Keycloak client,
an api-keys row, or a static entry is the adapter's problem, not the core's.

**Policy** — composable and attachable to routes/consumers/tenants without code: authentication,
authZ, rate-limit, transformation, caching, retry, circuit-breaker, timeout, validation.

**Plugin** — the unit a policy runs as; a link in the §7 pipeline.

---

## 10. The request context

One object threads the whole pipeline (the gateway's analogue of the modulith's `CurrentUser` + MDC):

```
RequestContext { requestId · correlationId · traceId · spanId ·
                 tenant · consumer · principal · route · matchedService ·
                 scopes/roles · metadata }
```

It is built up stage by stage (tenant resolution sets `tenant`, auth sets `principal`) and is what
transformation uses to inject `X-Tenant-Id` / `X-Consumer-Id` downstream. It is **request-scoped and
never persisted** — statelessness rule (§2).

---

## 11. Configuration, errors, observability

**Configuration.** Routes/services/policies from YAML → config server → admin API, in that order of
authority; reloadable where practical. Secrets from the environment / a secrets manager, never in
route config.

**Errors.** One envelope for every gateway-generated error, distinct from backend errors, reusing the
platform's `code`/`detail` shape so clients switch on a stable code: `401` unauthorized, `403`
forbidden, `404` no route, `413` too large, `429` rate limited, `502` bad gateway, `503` unavailable
(circuit open), `504` timeout. Every error carries the request id.

**Observability.** Every request emits request-id, correlation-id, trace-id, duration, status,
consumer, tenant, route, backend, response size. Metrics (RPS, error rate, p50/p95/p99, active
connections, per-route utilization, authN/authZ failures, backend failures) export to Prometheus /
OpenTelemetry. Logs are separated by kind — access, security, audit, gateway, system — never mixed.
Traces propagate `traceId`/`spanId` across every backend so one id spans gateway → service → service.

---

## 12. Testing philosophy

Inherits the repo's rule: **infra-touching tests use real containers, no fakes** (ADR 0003).

- **Unit** — filters, policies, predicates, the pipeline order (pure, no I/O).
- **Integration** — routing, auth, rate limiting against a **Keycloak** Testcontainer (JWKS/OIDC), a
  **Valkey** Testcontainer (buckets), and a **WireMock/MockWebServer** backend (never the real
  modulith in a gateway unit test).
- **Contract** — the gateway-integration API (introspection, quota, audit) has consumer-driven
  contract tests so the modulith and the adapter cannot drift.
- **Load** — throughput and p99 latency budgets per route; the gateway is on every request's hot path.
- **Chaos** — backend kill / latency injection proves the resilience policies (circuit break, retry,
  timeout, failover) actually fire.

---

## 13. What differentiates gateway vs API management vs service mesh

The gateway is **north-south** (client ↔ platform). It is **not** a service mesh (east-west,
service ↔ service, mTLS everywhere) and it is **not yet** full API management (developer portal, API
lifecycle, monetization) — those are Phase 6–7 capabilities layered on the same core, not a different
product. Keeping the three straight avoids scope creep: build the gateway first; grow management onto
it; leave east-west to a mesh if and when it is needed.
