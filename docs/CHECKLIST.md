# Build Checklist

Living checklist for [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). Checked = implemented **and**
its acceptance gate verified. Updated as work lands — this file is the single place to see progress.

## Phase 0 — Platform skeleton ✅ (2026-07-27)

- [x] Gradle 9.6.1 wrapper, Kotlin DSL, version catalog (`gradle/libs.versions.toml`)
- [x] foojay toolchain resolver — Java 21 auto-provisioned
- [x] Root `build.gradle.kts` — Boot 4.1.0 + Modulith 2.1.0 via `platform()` BOMs
- [x] `Application.java` (`@Modulithic @SpringBootApplication`)
- [x] `ModularityTests` (`ApplicationModules.verify()`) + context-loads test
- [x] `application.yaml` — `server.error.*` hardening, probe groups, `${ENV:default}` placeholders
- [x] Test profile disabling OTLP export (tests never need a collector)
- [x] `docker/docker-compose.yml` — postgres 18.4-alpine + grafana/otel-lgtm 0.28.0
- [x] `docker/.env.example` (+ local `.env` override; this machine: `POSTGRES_PORT=15432`)
- [x] `.gitignore`, `gradle.properties`, `README.md`, ADR `0001-platform-baseline`
- [x] git init (main), GitHub Actions CI (`.github/workflows/ci.yml`)
- [x] **Gate:** `./gradlew build` green
- [x] **Gate:** `bootRun` starts; `/actuator/health` + liveness/readiness UP
- [x] Initial commit

## Phase 1 — Core runtime (shared kernel)

- [x] `shared/web` — envelope records (`ApiResponse`/`ApiError`/`ApiSource`/`ApiMeta`/`PageMeta`)
- [x] `shared/web` — `ResponseBodyAdvice` auto-wrapper (excludes springdoc/actuator)
- [x] `shared/web` — `RequestIdFilter` (validate inbound id else mint ULID) + `X-Request-Id` header
- [x] `shared/error` — `ErrorCode` enum + `ApiException` hierarchy
- [x] `shared/error` — `GlobalExceptionHandler` (multi-error 422, catch-all 500 = only trace sink)
- [x] Tests: envelope wrapping, `meta.requestId`, **no stack-trace leakage** (MockMvc + ArchUnit rules)
- [x] `shared/observability` — `logback-spring.xml` JSON (human console for local/test), requestId+traceId in MDC, OTLP export, micrometer-java21 + virtual threads
- [x] `shared/security` — Keycloak 26.7.0 in Compose + realm import (smsone); OAuth2 Resource Server
- [x] `shared/security` — JWT roles → `ROLE_*`, `@EnableMethodSecurity`, `CurrentUser` + resolver, `PermissionEvaluator` seam; 401/403 as envelope; real-JWT gate test vs Keycloak container (dasniko dropped — TC1-only)
- [x] `shared/persistence` — `BaseEntity`/`AggregateRoot`, UUID keys, auditing, soft-delete
- [x] `shared/persistence` — Flyway (starter + postgresql module) `V1__baseline.sql`
- [x] Testcontainers: Postgres `@ServiceConnection` singleton — **real containers, no H2/fakes** (Keycloak container lands with security)
- [x] `OpenApiConfig` (bearer + Keycloak OAuth2 schemes, X-Request-Id header on all ops) + `./gradlew exportOpenApi` → `docs/openapi/*` (Postman imports the 3.1 spec natively)
- [x] First business module (**settings**: entity/service/controller/`SettingChanged` event, role-gated writes); `shared` marked OPEN; DB-backed event publication registry (Modulith JDBC v2 schema via Flyway)
- [x] **Gate:** secured endpoint validates a real Keycloak JWT; `verify()` passes with 2 modules; spec exports; `bootRun` health UP with 401-envelope on secured routes

## Phase 2 — API surface + Files ✅ (2026-07-27)

- [x] **Cursor pagination** (user decision, supersedes offset): `page[size]` + `page[after]` keyset cursors, `meta.page {size,count,hasMore,nextCursor}` + `links.next`; Spring Data `Window` scrolling; 422 on bad cursors
- [x] `files` module — `FileStorageProvider` + `S3StorageProvider` (AWS S3 v2, path-style) + presigner + idempotent bucket bootstrap (REST surface deferred until a consuming feature defines it)
- [x] SeaweedFS 4.40 (s3-config.json — no MINIO_* env shortcut) + mailpit in Compose
- [x] Testcontainers IT vs real SeaweedFS: put/get/delete/presign(GET+PUT via raw HTTP)/11MB multipart
- [x] **Gate:** storage flows green against SeaweedFS; cursor-paginated envelope verified end-to-end

## Phase 3 — Caching, scheduling, resilience, patterns ✅ (2026-07-27)

- [x] Valkey 8 in Compose (`service-connection: redis` label); two-level cache: Caffeine L1 + Valkey L2, pub/sub cross-instance L1 invalidation, L2 outage degrades gracefully
- [x] Scheduler module — `@Scheduled` + ShedLock JDBC (`usingDbTime`, TIMESTAMP-no-tz DDL); event-registry + idempotency purge jobs
- [x] Resilience4j — **`resilience4j-spring-boot4` 2.4.0** (research: `-spring-boot3` fail-fasts on Boot 4; Boot 4 dropped starter-aop → aspectjweaver); storage circuit breaker
- [x] Idempotency-key store (claim-first, replay, 409 on payload mismatch); Inbox (`EventInbox` idempotent consumer — outbox = Modulith registry); RFC 9457 content-negotiated adapter; DB-backed feature flags with cached evaluator
- [x] **Gate:** job fires once across 2 instances; cache verified (incl. real mid-flight Valkey outage); breaker opens under fault
- [x] Adversarial review (3 lenses, 17 verified findings, 14 confirmed & fixed): per-principal idempotency keys + takeover lease + 4xx-not-stored + body cap; tight Lettuce timeouts; no-broadcast-on-failed-L2-evict; breaker scoped to remote calls with not-found ignored; problem+json negotiation in filters; single-error pointers kept; framework headers preserved

## Phase 3A — Embedded analytics ✅ (2026-07-27)

- [x] `analytics` module — DuckDB 1.5.5.0, `AnalyticsEngine` seam, guarded durable connection + capped throwaway in-memory DBs (permit-bounded), thread/memory caps (global per instance — verified), native Parquet snapshots (statically linked, no network)
- [x] Marts: cursor-streamed materialization from Postgres (autocommit off), `DECIMAL(p,s)` fidelity (no double drift), `TIMESTAMPTZ` + pinned UTC sessions (host-independent day buckets), atomic staging swap (failed refresh leaves the old mart), traversal-proof snapshot paths
- [x] **Gate:** KPI aggregates over Postgres-born data via DuckDB with caps applied; adversarial review — 8 confirmed findings fixed with regression tests (exact money sums, UTC buckets, swap survival)

## Phase 4 — Documentation ✅ (2026-07-27)

- [x] Modulith Documenter → `docs/modulith/` (C4 `components.puml` + per-module PlantUML + canvases with events), refreshed every build, `./gradlew exportModulithDocs`
- [x] `docs/ARCHITECTURE.md` (Mermaid module map + request-path sequence + contract index); `docs/EVENTS.md` event catalog
- [x] ADR backlog written: 0002 cursor pagination · 0003 Testcontainers-only · 0004 two-level cache · 0005 idempotency keys · 0006 embedded DuckDB
- [x] `docs/NEXT_TASKS.md` — pickup-ready backlog for the next agent (CI, notification, identity/organization, K8s, rate limiting, event externalization)

---

**All planned phases (0–4) complete and gated.** Remaining work: [NEXT_TASKS.md](NEXT_TASKS.md).
