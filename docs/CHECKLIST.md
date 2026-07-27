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
- [ ] `OpenApiConfig` + `./gradlew exportOpenApi` → `docs/openapi/*` (imports into Postman)
- [ ] First business module; `shared` marked OPEN; DB-backed event publication registry
- [ ] **Gate:** secured endpoint validates Keycloak JWT; `verify()` passes with ≥1 module; spec exports

## Phase 2 — API surface + Files

- [ ] `page[number]`/`page[size]`/`sort` resolver; collections return `meta.page` + `links`
- [ ] `files` module — `FileStorageProvider` + `S3StorageProvider` (AWS S3 v2) + presigner + bucket bootstrap
- [ ] SeaweedFS 4.40 (s3-config.json) + mailpit in Compose
- [ ] Testcontainers IT vs real SeaweedFS: put/get/delete/presign/multipart
- [ ] **Gate:** upload/download/presign works; paginated envelope verified

## Phase 3 — Caching, scheduling, resilience, patterns

- [ ] Valkey 8 in Compose (`service-connection: redis` label); Caffeine L1 + Valkey L2
- [ ] Scheduler module — `@Scheduled` + ShedLock JDBC
- [ ] Resilience4j 2.4.0 — smoke-test on Boot 4.1 first
- [ ] Idempotency-key store; Outbox/Inbox; RFC 9457 adapter; DB-backed feature flags
- [ ] **Gate:** job fires once across 2 instances; cache verified; breaker opens under fault

## Phase 3A — Embedded analytics

- [ ] `analytics` module — DuckDB, guarded connection, thread/memory caps, Parquet snapshots
- [ ] **Gate:** KPI query over Postgres data without starving the JVM

## Phase 4 — Documentation

- [ ] Modulith Documenter diagrams, C4/PlantUML, event catalog, ADR backlog
