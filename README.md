# enterprise-modulith-template

Opinionated, enterprise-grade **Spring Boot 4.1 + Spring Modulith 2.1** template on **Java 21**.
A modular monolith with a future microservice-extraction path: modules own their data, communicate
through events, and hide infrastructure behind interfaces.

- Roadmap (the *what*): [docs/Enterprise_Spring_Modulith_Template_Roadmap_v2.md](docs/Enterprise_Spring_Modulith_Template_Roadmap_v2.md)
- Implementation plan (the *how* — pinned versions, contracts, build order): [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md)
- Progress checklist (ticked as gates pass): [docs/CHECKLIST.md](docs/CHECKLIST.md)
- Architecture (module map, request path, generated C4/PlantUML): [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Event catalog: [docs/EVENTS.md](docs/EVENTS.md) · Decisions: [docs/adr/](docs/adr/)
- Next work, pickup-ready: [docs/NEXT_TASKS.md](docs/NEXT_TASKS.md)

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
├── shared/               # OPEN kernel: envelope, errors, security, persistence, cache, idempotency, events
├── settings/             # key/value configuration + feature flags (SettingChanged, FeatureFlagChanged)
├── files/                # FileStorageProvider → SeaweedFS/S3 (presign, multipart, circuit breaker)
├── scheduler/            # ShedLock-guarded cron (event-registry + idempotency purges)
└── analytics/            # AnalyticsEngine → embedded DuckDB (marts, KPIs, Parquet snapshots)
```

`ApplicationModules.verify()` runs in the test suite on every build — boundary violations fail the build.

## Status

Phases 0–4 complete and gated — see [docs/CHECKLIST.md](docs/CHECKLIST.md). Remaining work is
queued in [docs/NEXT_TASKS.md](docs/NEXT_TASKS.md) (CI verification, notification/identity/
organization modules, Kubernetes migration, rate limiting, event externalization).
