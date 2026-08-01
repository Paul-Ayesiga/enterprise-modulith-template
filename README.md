# enterprise-modulith-template

Opinionated, enterprise-grade **Spring Boot 4.1 + Spring Modulith 2.1** template on **Java 21**.
A modular monolith with a future microservice-extraction path: modules own their data, communicate
through events, and hide infrastructure behind interfaces.

**Documentation index: [docs/README.md](docs/README.md).** The most-used entries:

- Engineering standards (read §1 before writing code here): [AGENTS.md](AGENTS.md)
- Architecture (module map, request path, generated C4/PlantUML): [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Run & poke it locally (URLs, tokens, every endpoint): [docs/LOCAL_ACCESS.md](docs/LOCAL_ACCESS.md)
- Progress (gates ticked as they pass): [docs/CHECKLIST.md](docs/CHECKLIST.md)

## Stack

| Concern | Choice |
|---|---|
| Platform | Spring Boot 4.1, Spring Modulith 2.1, Java 21, Gradle 9.6 (Kotlin DSL) |
| OLTP | PostgreSQL 18 + Flyway |
| Cache / locks | Valkey 8 (Redis-compatible) + Caffeine L1 |
| Object storage | SeaweedFS via AWS S3 v2 SDK (works with any S3: AWS, R2, B2) |
| Analytics | DuckDB (embedded, in-process OLAP) |
| AuthN/Z | Keycloak (OIDC), OAuth2 Resource Server, method security |
| Observability | Actuator + OpenTelemetry (OTLP) → grafana/otel-lgtm |
| API contract | JSON:API-inspired lite envelope; `meta.requestId` on every response; no stack traces on the wire |

## Quickstart

Prereqs: Docker (with Compose), a JDK (the build auto-provisions the Java 21 toolchain via foojay).

```bash
./gradlew build          # compiles, runs tests incl. Modulith verify()
./gradlew bootRun        # starts the app; Docker Compose services auto-start
```

Then check:

- Health: <http://localhost:8080/actuator/health> (`/health/liveness`, `/health/readiness`)
- Grafana (LGTM stack): <http://localhost:3000>

Environment overrides: copy `docker/.env.example` to `docker/.env` (e.g. change `POSTGRES_PORT`
if 5432 is taken on your machine). Every infra coordinate is a `${ENV:default}` value, so the
Kubernetes migration later is config, not code.

## Module layout

Spring Modulith application modules are Java packages under `ug.co.smsone`:

```text
ug.co.smsone
├── Application.java      # @Modulithic @SpringBootApplication (module root, not a module)
├── shared/               # OPEN kernel: envelope, errors, security, persistence, cache, idempotency, rate limiting, events
├── settings/             # key/value configuration + feature flags (SettingChanged, FeatureFlagChanged)
├── localization/         # translation catalog + Messages port (exact → language → default → key fallback)
├── search/               # Postgres FTS projection (tsvector GIN + trigram fallback) + SearchIndex port
├── files/                # FileStorageProvider → SeaweedFS/S3 (presign, multipart, circuit breaker)
├── scheduler/            # ShedLock-guarded cron (event-registry, idempotency + soft-delete retention purges)
├── analytics/            # AnalyticsEngine → embedded DuckDB (marts, KPIs, Parquet snapshots)
├── notification/         # pluggable channels (email/in-app/webhook/Slack/SMS), durable async fan-out
├── identity/             # Keycloak user projection, admin-driven provisioning (no JIT), audited impersonation
├── organization/         # Keycloak Organizations projection + org-scoped RBAC authority
├── audit/                # append-only who/when/where/what/from→to trail behind the AuditLog port
└── webhooks/             # per-org outbound event subscriptions (HMAC-signed, durable delivery)
```

`ApplicationModules.verify()` runs in the test suite on every build — boundary violations fail the build.

## Status

Phases 0–4 complete and gated, and all ten modules are shipped — see
[docs/CHECKLIST.md](docs/CHECKLIST.md) and [docs/COMPLETED_MODULES.md](docs/COMPLETED_MODULES.md).
Remaining: CI verification, the Kubernetes migration, and event externalization.
