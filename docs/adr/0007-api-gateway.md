# ADR 0007 — API Gateway: a reactive Spring Cloud Gateway as a hexagonal platform product

- **Status:** Accepted · Implemented (all 7 phases shipped) · **Date:** 2026-08-02

## Decision
The platform's front door is a **separate, stateless, reactive Spring Cloud Gateway** (WebFlux)
deployable — not a feature of the modulith. It is structured **hexagonally**: `gateway-core` owns the
edge concepts (routes, services, consumers, the plugin/policy engine, the request pipeline) and
defines **ports**; the platform plugs in through **adapters**. The core depends on no platform
module; the platform depends on the gateway's contracts (dependency direction points *inward*, the
Spring Security / Spring Data `UserDetailsService` / `Dialect` pattern).

Because the gateway is a separate **reactive** process while the modulith is **servlet + blocking
JDBC**, adapters integrate over **network contracts**, never in-process calls: JWTs verify against
Keycloak's JWKS (no platform call), API keys and quotas resolve over a thin HTTP introspection
surface or a shared Valkey snapshot, and audit events publish to a sink. The gateway performs
**coarse** edge authorization (authenticated? correct tenant? required scope/role?); services keep
**fine-grained** `hasPermission` (defense in depth). It ships in phases — Core → Security → Traffic
→ Observability → Extensibility → Admin → Enterprise — and is useful from Phase 1.

The full design is [../GATEWAY_ARCHITECTURE.md](../GATEWAY_ARCHITECTURE.md); the phased delivery is
[../GATEWAY_PLAN.md](../GATEWAY_PLAN.md).

## Why
Every service otherwise reimplements routing, security, rate limiting, CORS, request validation, and
observability. The modulith already proves these edge patterns in-process — `RateLimitFilter`,
`ApiKeyAuthenticator`, `OrgAuthorization`, `AuditLog`, the ordered filter chain — so lifting them to a
true edge is the natural next step as the modulith later splits into services. A **hexagonal** core
keeps the gateway reusable, independently testable, and open-sourceable while reusing platform
capabilities (api-keys as consumers, subscriptions/entitlements as quotas, audit, webhooks, Keycloak,
Valkey) with **zero duplicated stores**. **Reactive + stateless** so it scales horizontally and never
degrades into "another platform application" holding tenant state.

Rejected alternatives: *adopt APISIX/Kong* (a second runtime and Lua/WASM extension stack — revisit
only if OOB feature breadth outweighs one-language ownership); *enrich the modulith's edge in-process*
(can never front separate services — a stopgap, not a gateway); *deep in-process reuse* (couples the
gateway to the platform and makes it another app); *fully standalone duplication* (two consumer/audit
systems to reconcile). Hexagonal ports-and-adapters is the third path that avoids both extremes.

## Consequences
- **Build becomes multi-project.** Gateway Gradle subprojects (`core`, `security`, `platform-adapter`,
  `admin`, `starter`, `app`) live alongside the modulith; the current single-project build gains a
  `settings.gradle.kts` include graph. The modulith is untouched.
- **A new reactive stack** (Spring Cloud Gateway / WebFlux / reactive Redis) whose release train must
  track Spring Boot 4.1 — a pinning/compatibility risk called out in the plan.
- **The modulith grows a thin gateway-integration surface** (key introspection, quota lookup, audit
  ingestion) that *backs* the adapters and is itself backed by the existing ports.
- **The coarse/fine authorization split is load-bearing:** services MUST keep their own
  `hasPermission` checks — the gateway is defense in depth at the edge, never the only gate.
- **Hard rules:** the gateway MUST NOT touch a business database, run domain workflows, persist
  business entities, or hold per-request tenant state. Violations turn the edge into an app.

## Implementation (2026-08-02)
All seven phases shipped — Core, Security, Traffic, Observability, Extensibility, Administration, and the
gateway-side of Enterprise (lifecycle/versioning, products/catalog/OpenAPI). The realized subproject
layout is `gateway:core`, `gateway:security`, `gateway:platform-adapter`, `gateway:app`: `admin` and
`starter` (see Consequences) were **folded into `app`** — admin surfaces as actuator management endpoints
on a separate port, and components are scanned, so no separate starter was needed. The modulith grew the
predicted thin integration surface as three `@Hidden`, gateway-secret-authed endpoints, each backed by an
existing port: key introspection (`ApiKeyAuthenticator`), audit ingest (`AuditLog.recordExternal`), quota
lookup (`Entitlements.limitOf`). The hexagonal boundary — the core depending on neither Spring Cloud
Gateway nor any platform module — is enforced by an ArchUnit test that ships with `gateway:core`. Enterprise
deliverables that are separate deployables/infra (a developer-portal UI, multi-region failover) are out of
the gateway codebase by design, flagged not dropped. Per-phase detail: [../GATEWAY_PLAN.md](../GATEWAY_PLAN.md).
