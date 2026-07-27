# Enterprise Spring Modulith Template — Implementation Plan

> Companion to `Enterprise_Spring_Modulith_Template_Roadmap_v2.md`. The roadmap says **what** to
> build; this plan pins **exact versions, structure, contracts, and build order**. All versions were
> web-verified against Maven Central / vendor registries on **2026-07-27** (HTTP 200 confirmed for
> every pinned coordinate).

---

## 1. Locked decisions

| Decision | Choice | Rationale |
|---|---|---|
| Platform anchor | **Spring Boot 4.1.0** + **Spring Modulith 2.1.0** | Current GA. Boot 3.5 hit OSS EOL 2026‑06‑30 — a new template on 3.5 ships already-EOL. 4.1 → Spring Framework 7, Jakarta EE 11, Hibernate 7. |
| Language / runtime | **Java 21** (LTS) | As specified in the roadmap. Boot 4.1 baseline is 17, tested to 26; raising to 25 later is a zero-dependency toolchain bump. |
| Build | **Gradle 9.6.1, Kotlin DSL**, version catalog + Gradle-native BOM `platform()` imports | 100% Gradle. No Maven build tool. No `io.spring.dependency-management` plugin (use `platform()` — cleaner on Gradle 9). |
| Group / base package | **`ug.co.smsone`** | Reverse-DNS of smsone.co.ug. Supersedes the roadmap's `io.commuza` placeholder. |
| Cache / lock backend | **Valkey 8** (BSD) | Wire-compatible Redis drop-in; avoids Redis 8's AGPL/SSPL exposure in a redistributable template. |
| Object storage | **SeaweedFS** (Apache-2.0) | Replaces MinIO (MinIO stopped free Docker images Oct 2025). Driven by the **AWS S3 v2 SDK** — same code for local SeaweedFS, self-hosted, and managed S3/R2/B2. |
| Analytics engine | **DuckDB 1.5.5.0** embedded (Phase 3A) | In-process OLAP; not a container. |
| API response contract | **Unified JSON:API-inspired *lite* envelope**, `application/json` | One shape for success **and** error; `meta.requestId` on every response; no stack-trace leakage. |
| Config format | **YAML** for all Spring config (`application.yaml`, `logback-spring.xml`). `gradle.properties` is Gradle's own build-tuning file (no YAML equivalent) — not app config. |
| Deployment | **Docker Compose now, Kubernetes later** | Keep DRY by externalizing all infra coordinates as `${ENV:default}` Spring properties. |

### Configuration format note (YAML)
Every piece of Spring configuration is YAML. The **only** `.properties` file in the whole project is
`gradle.properties`, which configures the Gradle build daemon (parallelism, JVM heap) and has no YAML
form. It contains nothing about the running application.

---

## 2. Dependency catalog

Two BOMs manage ~80% of versions. Only ~10 libraries are pinned explicitly.

### 2.1 `gradle/libs.versions.toml` (proposed)

```toml
[versions]
# --- Anchors ---
springBoot = "4.1.0"
springModulith = "2.1.0"
java = "21"

# --- Pinned (NOT managed by any BOM) ---
springdoc = "3.0.3"          # OpenAPI 3.1 + Swagger UI — 3.0.x is the Boot 4 line (2.8.x = Boot 3)
mapstruct = "1.6.3"
archunit = "1.4.2"
shedlock = "7.7.0"
logstashEncoder = "9.0"
awssdk = "2.49.3"            # S3 v2 SDK BOM — drives SeaweedFS + managed S3
tcKeycloak = "4.1.1"        # community dasniko Testcontainers module
duckdb = "1.5.5.0"          # embedded OLAP (Phase 3A)
ulid = "5.2.3"              # sortable request IDs

# --- Deferred / verify-before-commit on Boot 4.1 ---
resilience4j = "2.4.0"      # Phase 3 — artifact still named -spring-boot3; smoke-test first
bucket4j = "8.14.0"        # Phase 5 — note the _jdk17 artifact suffix

[libraries]
boot-bom            = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "springBoot" }
spring-modulith-bom = { module = "org.springframework.modulith:spring-modulith-bom", version.ref = "springModulith" }
awssdk-bom          = { module = "software.amazon.awssdk:bom", version.ref = "awssdk" }

# Spring Boot starters (BOM-managed — no version)
boot-web            = { module = "org.springframework.boot:spring-boot-starter-web" }
boot-validation     = { module = "org.springframework.boot:spring-boot-starter-validation" }
boot-actuator       = { module = "org.springframework.boot:spring-boot-starter-actuator" }
boot-security       = { module = "org.springframework.boot:spring-boot-starter-security" }
boot-oauth2-rs      = { module = "org.springframework.boot:spring-boot-starter-oauth2-resource-server" }
boot-data-jpa       = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
boot-data-redis     = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
boot-cache          = { module = "org.springframework.boot:spring-boot-starter-cache" }
boot-otel           = { module = "org.springframework.boot:spring-boot-starter-opentelemetry" }
boot-test           = { module = "org.springframework.boot:spring-boot-starter-test" }
boot-docker-compose = { module = "org.springframework.boot:spring-boot-docker-compose" }
boot-testcontainers = { module = "org.springframework.boot:spring-boot-testcontainers" }

# Spring Modulith (BOM-managed)
modulith-core          = { module = "org.springframework.modulith:spring-modulith-starter-core" }
modulith-test          = { module = "org.springframework.modulith:spring-modulith-starter-test" }
modulith-actuator      = { module = "org.springframework.modulith:spring-modulith-actuator" }
modulith-observability = { module = "org.springframework.modulith:spring-modulith-observability" }

# Persistence (BOM-managed, except DuckDB)
postgresql        = { module = "org.postgresql:postgresql" }
flyway-core       = { module = "org.flywaydb:flyway-core" }
flyway-postgresql = { module = "org.flywaydb:flyway-database-postgresql" }  # MANDATORY companion since Flyway 10
caffeine          = { module = "com.github.ben-manes.caffeine:caffeine" }
duckdb            = { module = "org.duckdb:duckdb_jdbc", version.ref = "duckdb" }

# Pinned libraries
springdoc        = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
mapstruct        = { module = "org.mapstruct:mapstruct", version.ref = "mapstruct" }
mapstruct-processor = { module = "org.mapstruct:mapstruct-processor", version.ref = "mapstruct" }
archunit         = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
shedlock-spring  = { module = "net.javacrumbs.shedlock:shedlock-spring", version.ref = "shedlock" }
shedlock-jdbc    = { module = "net.javacrumbs.shedlock:shedlock-provider-jdbc-template", version.ref = "shedlock" }
logstash-encoder = { module = "net.logstash.logback:logstash-logback-encoder", version.ref = "logstashEncoder" }
ulid-creator     = { module = "com.github.f4b6a3:ulid-creator", version.ref = "ulid" }

# Storage (Files module) — AWS S3 v2 via BOM
awssdk-s3            = { module = "software.amazon.awssdk:s3" }
awssdk-apache-client = { module = "software.amazon.awssdk:apache-client" }

# Test — Testcontainers 2.x names (Boot 4.1 BOM pins TC 2.0.5; modules gained a testcontainers- prefix)
testcontainers-postgres = { module = "org.testcontainers:testcontainers-postgresql" }
testcontainers-junit    = { module = "org.testcontainers:testcontainers-junit-jupiter" }
testcontainers-keycloak = { module = "com.github.dasniko:testcontainers-keycloak", version.ref = "tcKeycloak" }

# Deferred
resilience4j = { module = "io.github.resilience4j:resilience4j-spring-boot3", version.ref = "resilience4j" }
bucket4j     = { module = "com.bucket4j:bucket4j_jdk17-core", version.ref = "bucket4j" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
```

### 2.2 Root `build.gradle.kts` (proposed, Phase 0 wiring)

```kotlin
plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "ug.co.smsone"
version = "0.1.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt()) } }

repositories { mavenCentral() }

dependencies {
    implementation(platform(libs.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.boot.web)            // + validation, springdoc  (web-api bundle)
    implementation(libs.boot.validation)
    implementation(libs.springdoc)
    implementation(libs.modulith.core)
    implementation(libs.boot.actuator)       // observability bundle
    implementation(libs.boot.otel)
    implementation(libs.modulith.actuator)
    implementation(libs.modulith.observability)
    implementation(libs.logstash.encoder)
    implementation(libs.ulid.creator)

    developmentOnly(libs.boot.docker.compose)

    testImplementation(libs.boot.test)
    testImplementation(libs.modulith.test)
    testImplementation(libs.archunit)
    // Later phases: data-jpa/flyway/postgresql, security/oauth2-rs, awssdk s3, data-redis/cache/caffeine,
    //               shedlock, duckdb, resilience4j, bucket4j, testcontainers-*  (all in the catalog above)
}
```

### 2.3 Pin-vs-BOM master list

- **BOM-managed (declare with NO version):** every `spring-boot-starter-*`, `spring-modulith-*`, `postgresql`,
  `flyway-core` + `flyway-database-postgresql`, `hibernate-core` (→ 7.x), `lettuce-core`, `caffeine`,
  `micrometer-*`, `opentelemetry-exporter-otlp`, `nimbus-jose-jwt` (**never** pin by hand), JUnit/AssertJ/Mockito,
  `org.testcontainers:postgresql` + `:junit-jupiter`, `software.amazon.awssdk:s3` (via awssdk BOM).
- **Pinned explicitly:** `spring-modulith-bom` 2.1.0, `duckdb_jdbc` 1.5.5.0, `springdoc` 3.0.3, `mapstruct` 1.6.3,
  `archunit-junit5` 1.4.2, `shedlock` 7.7.0, `logstash-logback-encoder` 9.0, `testcontainers-keycloak` 4.1.1,
  `awssdk:bom` 2.49.3, `ulid-creator` 5.2.3. **(Deferred/verify-first:** `resilience4j` 2.4.0, `bucket4j` 8.14.0.)

### 2.4 Deliberately rejected (with reason)

| Rejected | Instead | Why |
|---|---|---|
| Lombok | records + constructor injection | Matches the "constructor injection only" principle; avoids MapStruct processor-ordering pain. |
| zalando problem-spring-web | Spring-native `ProblemDetail` (RFC 9457) via content negotiation | First-class in Spring 7, zero deps. |
| Togglz | DB-backed `feature_flag` table + cached evaluator | No confirmed Boot 4 build yet. |
| Apache Arrow / Parquet libs | DuckDB native Parquet/CSV/JSON I/O | Also dodges the `--add-opens` JVM flag. |
| MinIO Java SDK | AWS S3 v2 SDK | One HTTP stack, not two; SeaweedFS speaks S3. |
| A JSON:API library (Elide/Katharsis/foundation-jsonapi) | hand-rolled lite envelope | They impose full JSON:API + fight Boot 4.1; the envelope is ~6 records + 1 advice + 1 filter + 1 handler. |
| standalone `testcontainers-bom` | Boot BOM's pin | Avoids version drift. |

---

## 3. Project / module structure

**One Gradle module.** Spring Modulith application modules are Java **packages** under `ug.co.smsone.*`
(this matches the roadmap's single `build.gradle.kts` and is the idiomatic, verifiable Modulith layout).
Physical Gradle-module splitting is a deliberate later step, not a prerequisite for a modular monolith.

```
ug.co.smsone
├── Application.java                 @Modulithic @SpringBootApplication
├── shared/                          ← shared kernel (one Modulith module; becomes OPEN when business modules land)
│   ├── web/                         ApiResponse, ApiError, ApiSource, ApiMeta, PageMeta, ResourceObject,
│   │                                ResponseBodyAdvice auto-wrapper, RequestIdFilter, ApiMetaFactory, OpenApiConfig
│   ├── error/                       ErrorCode enum, ApiException + Not/Conflict/Forbidden/Unauthorized/Validation,
│   │                                GlobalExceptionHandler (@RestControllerAdvice)
│   ├── config/                      ClockConfig, async/scheduling, jackson via YAML
│   ├── security/         (Phase 1)  CurrentUser, JwtAuthoritiesConverter, PermissionEvaluator, method security
│   ├── persistence/      (Phase 1)  BaseEntity, AggregateRoot, UUID keys, auditing, soft-delete, specifications
│   └── observability/    (Phase 1)  probe wiring, structured logging
└── <business modules>               (later) identity, organization, notification, files, scheduler,
                                      settings, localization, search, document, audit, health, analytics
```

- `Application` is the module root (not itself a module). For **Phase 0** the only module is `shared`, so
  `ApplicationModules.of(Application.class).verify()` passes trivially.
- When the first business module lands (Phase 1), `shared` is marked **OPEN** (`@ApplicationModule(type = OPEN)`
  in `shared/package-info.java`, exact API confirmed against the Modulith 2.1.0 jar at that point) so modules may
  depend on shared infrastructure without boundary violations.

---

## 4. Cross-cutting contracts

### 4.1 Response envelope — JSON:API-inspired *lite*

**Why not the alternatives:** JSON:API's `errors[]` and RFC 9457 `problem+json` are different content types
and shapes, and **RFC 9457 has no success half** — so it cannot satisfy "one structure for both." Full JSON:API
(`relationships`/`included`/sparse fieldsets/`vnd.api+json` negotiation) is cost with no SPA payoff. The lite
envelope is a strict subset — a module can opt into full JSON:API later without reshaping clients.

**Wire shape (both directions):** `{ data | errors, meta, links }` — `data` **XOR** `errors`;
`meta.requestId` **always** present; served as `application/json`; `X-Request-Id` header on **every** response.

Success (single resource, 200):
```json
{
  "data": { "id": "7d3f…", "type": "user",
            "attributes": { "email": "david@smsone.co.ug", "status": "ACTIVE" } },
  "meta": { "requestId": "01J9Z8Q7Y3M4K5N6P7R8S9T0AB", "timestamp": "2026-07-27T09:14:03.512Z", "apiVersion": "1" },
  "links": { "self": "/api/v1/users/7d3f…" }
}
```

Error (multi-error validation, 422):
```json
{
  "errors": [
    { "id": "01J9…-1", "status": "422", "code": "VALIDATION_EMAIL_INVALID",
      "title": "Invalid email address", "detail": "Must be a well-formed email address.",
      "source": { "pointer": "/data/attributes/email" } },
    { "id": "01J9…-2", "status": "422", "code": "VALIDATION_PAGE_SIZE_RANGE",
      "title": "Page size out of range", "detail": "page[size] must be between 1 and 100.",
      "source": { "parameter": "page[size]" } }
  ],
  "meta": { "requestId": "01J9…", "timestamp": "2026-07-27T09:14:03.512Z" }
}
```
`status` is a **string** (spec mandate). `code` is a stable enum key (client's switch value). `detail` is
**always** a curated string — never `ex.getMessage()`.

Pagination — **cursor/keyset (decided 2026-07-27, supersedes the original offset design)**:
`page[size]` (clamp ≤ 100, default 20) + `page[after]` (opaque base64url keyset cursor); response
carries `meta.page {size, count, hasMore, nextCursor}` and `links.next` (`null` when exhausted).
No totals — that's the point (no COUNT, stable under concurrent writes). Backed by Spring Data
keyset scrolling (`Window`/`KeysetScrollPosition`) over a stable unique sort (createdAt desc, id
desc); invalid cursors → 422 with `source.parameter: page[after]`.

Shared building blocks (`shared/web`, `shared/error`):
- `ApiResponse<T>` / `ApiError` / `ApiSource` / `ApiMeta` / `PageMeta` — records, null fields omitted via
  `spring.jackson.default-property-inclusion: non_null`.
- `ResponseBodyAdvice` auto-wrapper (`@RestControllerAdvice(basePackages="ug.co.smsone")` — excludes springdoc/actuator).
- `ErrorCode` enum (httpStatus, stable code, title) + `ApiException` hierarchy.
- `GlobalExceptionHandler extends ResponseEntityExceptionHandler` — overrides `handleMethodArgumentNotValid`
  (multi-error 422) and `handleExceptionInternal` (translate framework `ProblemDetail` → envelope); catch-all
  `@ExceptionHandler(Exception)` → fixed safe 500, the **only** place the stack trace is logged (with requestId).

### 4.2 requestId vs traceId (the "no log traces in responses" rule)

| | requestId | traceId / spanId |
|---|---|---|
| Audience | **public**, client-facing | **internal** only |
| On the wire | `meta.requestId` + `X-Request-Id` header | **never** (not in body, not in any header) |
| In logs | yes | yes |
| Generated by | `RequestIdFilter` (`OncePerRequestFilter`, HIGHEST_PRECEDENCE): validate inbound `X-Request-Id`/`X-Correlation-Id` (≤64 chars, `[A-Za-z0-9_-]`, anti log-forging) else mint a **ULID** | OTel/Micrometer Tracing auto-config |

Support pivots `requestId (from client) → log line → traceId → distributed trace`, entirely server-side.
Exposing `traceparent`/traceId outward leaks topology and allows span injection — so it stays off the wire.

### 4.3 Stack-trace leakage — blocked four ways
1. `application.yaml`: `server.error.include-stacktrace/message/binding-errors = never`, `include-exception: false`, whitelabel off.
2. Discipline: error `detail` is a curated constant or i18n key — never `ex.getMessage()`/`getCause()`/class name; `meta` never carries stackTrace/SQL/traceId/hostnames.
3. Single sink: the catch-all 500 handler is the only place the full trace is logged.
4. Tests: a MockMvc test asserting no response body contains `"Exception"` / `"at ug."`; ArchUnit `GeneralCodingRules` (no `System.out/err`, no generic-exception throws).

### 4.4 OpenAPI → Postman
- **Do not** use `springdoc-openapi-gradle-plugin` (latest 1.9.0, Aug 2024, no Boot 4 support, force-boots the whole app).
- Full `OpenApiConfig` bean: `info`, `servers` (local/staging/prod), a **Keycloak** security scheme — HTTP `bearer`/JWT **and** an OAuth2 `authorizationCode` flow pointing at the realm token URL so **Postman runs the OAuth2 flow directly** — plus a springdoc `OperationCustomizer` documenting the `X-Request-Id` response header on every operation.
- Export via an integration test (`@SpringBootTest(RANDOM_PORT)` hitting `/v3/api-docs.yaml` + `/v3/api-docs`) that writes `docs/openapi/openapi.yaml` + `openapi.json`, exposed as `./gradlew exportOpenApi`. Committed + versioned; **Postman imports the 3.1 spec natively** → collection + environment + preconfigured auth.

---

## 5. Infrastructure

### 5.1 Docker Compose now — `docker/docker-compose.yml`

`spring-boot-docker-compose` (developmentOnly) auto-starts these and injects `@ServiceConnection` for Postgres +
Valkey at dev time; the app runs from the IDE.

| Service | Image (pinned) | Ports | Purpose |
|---|---|---|---|
| postgres | `postgres:18.4-alpine` | 5432 | OLTP system-of-record |
| valkey | `valkey/valkey:8-alpine` (label `service-connection: redis`) | 6379 | Cache L2 + distributed locks |
| keycloak | `quay.io/keycloak/keycloak:26.7.0` (`start-dev`) | 8081→8080 | OIDC (embedded H2 in dev) |
| seaweedfs | `chrislusf/seaweedfs:4.40` (`server -s3 -s3.config=…`) | 8333 (S3), 8888, 9333 | S3 object storage |
| mailpit | `axllent/mailpit:v1.30.2` | 8025 (UI), 1025 (SMTP) | Email sink |
| otel-lgtm | `grafana/otel-lgtm:0.28.0` | 3000, 4317, 4318 | All-in-one Prometheus+Loki+Tempo+Grafana+OTel |

- **DuckDB is embedded — not a container.** The app is not containerized in daily dev.
- SeaweedFS needs a mounted `docker/seaweedfs/s3-config.json` (identities: `accessKey`/`secretKey` + `actions`) — there is no `MINIO_ROOT_USER` env shortcut.
- SeaweedFS client config: `endpointOverride(http://seaweedfs:8333)` + `forcePathStyle(true)` + `region(us-east-1)` + static creds. Same `S3Client` code for local/self-hosted/managed.

### 5.2 Kubernetes later — the migration is config, not code

Externalize all infra coordinates as `${ENV:default}` Spring properties so **only injected values change**;
Kustomize `configMapGenerator` reads the same `.env`; Actuator liveness/readiness is the single health contract.

| Concern | Compose now | K8s later (deferred) |
|---|---|---|
| Image build | `bootRun` (no image) | Buildpacks `bootBuildImage` in CI, deploy by digest |
| Config / secrets | `.env` + `environment:` | ConfigMap + Secret; `configMapGenerator` reuses the same `.env` |
| Health | `healthcheck:` | Actuator liveness/readiness → probes (wired **now**) |
| DB | container + volume | managed Postgres (RDS/CloudSQL) or CloudNativePG StatefulSet |
| Object storage | SeaweedFS all-in-one | SeaweedFS official chart (master/volume/filer/s3 split) **or** managed S3/R2/B2 |
| Scaling | 1 process | single Deployment + HPA; DB-backed Modulith event registry + Valkey locks make it safe |
| Packaging | `compose.yaml` | Kustomize base + overlays (Helm only if redistributed) |
| Observability | `otel-lgtm` all-in-one | OTel Operator + split LGTM Helm charts; app keeps emitting OTLP |
| Secrets mgmt | plaintext `.env` | External Secrets Operator (Vault for dynamic creds) |

**Build now (cost nothing, prerequisites for K8s):** Actuator probe groups, OTLP export, externalized config,
DB-backed Modulith event publication registry, Valkey distributed locks. **Defer:** all K8s manifests, Helm,
HPA, Ingress, External-Secrets/Vault, the OTel Operator, managed-DB migration.

---

## 6. Phased implementation roadmap

Each phase lists concrete deliverables and an acceptance gate. Phases map to the roadmap doc.

### Phase 0 — Platform skeleton
**Deliverables:** `settings.gradle.kts` (foojay toolchain resolver), `gradle/libs.versions.toml`, root
`build.gradle.kts` (Phase 0 wiring), Gradle 9.6.1 wrapper, `.gitignore`, `gradle.properties`; empty
`Application` booting on Boot 4.1/Java 21; `ModularityTests` (`ApplicationModules.verify()`); `docker/docker-compose.yml`
(postgres + otel-lgtm to start); `application.yaml` with `${ENV:default}` placeholders + `server.error.*` hardening
+ actuator probe groups; `README.md`; `docs/adr/0001-platform-baseline.md`.
**Gate:** `./gradlew build` green (context loads, `verify()` passes); `bootRun` starts and `/actuator/health` is UP.

### Phase 1 — Core runtime (shared kernel)
**Deliverables:**
- `shared/web` — envelope records, `ResponseBodyAdvice` auto-wrapper, `RequestIdFilter`, `ApiMetaFactory`, `OpenApiConfig`, `exportOpenApi` task + `docs/openapi/*`.
- `shared/error` — `ErrorCode`, `ApiException` hierarchy, `GlobalExceptionHandler`.
- `shared/observability` — Actuator liveness/readiness groups, `boot-otel` OTLP → `otel-lgtm:4318`, `logback-spring.xml` JSON with `requestId` + `traceId` in MDC; `micrometer-java21` for virtual-thread metrics.
- `shared/security` — OAuth2 Resource Server (`issuer-uri` → Keycloak realm), `JwtAuthenticationConverter` mapping `realm_access.roles`/`resource_access.<client>.roles` → `ROLE_` authorities, `@EnableMethodSecurity`, `CurrentUser` + `@CurrentUser` resolver, `PermissionEvaluator` seam. Keycloak realm import (`docker/keycloak/realm-smsone.json`).
- `shared/persistence` — `BaseEntity`/`AggregateRoot`, UUID keys, JPA auditing, soft-delete, specifications; Flyway **core + postgresql** module, `V1__baseline.sql`; Testcontainers Postgres `@ServiceConnection`.
- First real business module (prove the Modulith slice + ArchUnit rules + `@ApplicationModuleTest`); mark `shared` OPEN. DB-backed Modulith **event publication registry** enabled.
**Gate:** envelope + requestId + no-stack-trace tests green; a secured endpoint validates a Keycloak JWT; `verify()` still passes with ≥1 business module; OpenAPI spec exports and imports into Postman.

### Phase 2 — API surface + Files
**Deliverables:** controllers with `@Valid`, SpringDoc UI, `page[...]` pageable resolver; `files` module —
`FileStorageProvider` interface + `S3StorageProvider` (AWS S3 v2), `S3Presigner`, idempotent bucket bootstrap;
SeaweedFS + mailpit added to Compose; Testcontainers IT against real `chrislusf/seaweedfs:4.40` (put/get/delete/presign/multipart).
**Gate:** file upload/download/presign works against SeaweedFS; paginated collection returns the envelope's `meta.page` + `links`.

### Phase 3 — Caching, scheduling, resilience, enterprise patterns
**Deliverables:** Valkey (`boot-cache` + Caffeine L1 + Valkey L2); scheduler module (`@Scheduled` + ShedLock
**JDBC** provider); Resilience4j (after Boot-4.1 smoke-test) for outbound calls; DB-backed idempotency-key store;
Outbox/Inbox; RFC 9457 content-negotiated adapter (`Accept: application/problem+json`); feature flags (DB-backed).
**Gate:** scheduled job fires once across 2 app instances; cache hit/evict verified; circuit-breaker opens under fault injection.

### Phase 3A — Embedded analytics
**Deliverables:** `analytics` module — DuckDB 1.5.5.0, single guarded connection for durable marts, in-memory
DB for ephemeral queries, `SET threads`/`SET memory_limit` caps, Parquet snapshots (native I/O, no Arrow/Parquet libs).
**Gate:** an aggregate KPI query runs over Postgres data via DuckDB without starving the app JVM.

### Later
Phase 4 (docs: Modulith diagrams/C4/PlantUML/event catalog/ADRs), Phase 5 (future modules), and the Kubernetes
migration (Kustomize base + managed Postgres + external S3 + external-secrets) per §5.2.

---

## 7. Testing strategy
- **Modulith:** `ApplicationModules.verify()` on every build; `@ApplicationModuleTest` slices per module.
- **Architecture:** ArchUnit `GeneralCodingRules` (no `System.out/err`, no generic exceptions) + custom boundary rules.
- **Web contract:** MockMvc tests for envelope wrapping, `meta.requestId`, `X-Request-Id` header, and **no stack-trace leakage** (assert body excludes `"Exception"`/`"at ug."`).
- **Integration:** Testcontainers for Postgres (`@ServiceConnection`), Keycloak (dasniko), and SeaweedFS (generic container) — test real behavior, never trust S3 parity.
- **OpenAPI:** the export test doubles as a contract check (`/v3/api-docs.yaml` is non-empty and imports into Postman).

## 8. Risks & decisions to confirm

| # | Risk / decision | Recommendation |
|---|---|---|
| 1 | Boot 4.1 is very new → Hibernate 6.6→**7.x**, SpringDoc 2.8→**3.0**, Lettuce→7.x, Jakarta EE 11 | Accepted (anchor). Audit entity mappings for Hibernate 7 at Phase 1. |
| 2 | `resilience4j-spring-boot3` 2.4.0 & Togglz advertise Boot 3 | Smoke-test Resilience4j at Phase 3; **drop Togglz** for a DB-backed flag table. |
| 3 | SeaweedFS trails S3 on versioning/object-lock/ACLs/multipart edges | Integration-test the exact flows used; `forcePathStyle(true)` + correct creds fix most `SignatureDoesNotMatch`. |
| 4 | SeaweedFS `-s3.config` schema for the 4.40 tag | Sanity-check field names against the S3-Configuration wiki before prod. |
| 5 | Prod storage: self-hosted SeaweedFS vs managed S3/R2/B2 | Pure config decision (S3 abstraction) — pick per environment. |

### Micro-decisions taken as defaults (flip any on request)
`application/json` media type · **0-based** paging · **ULID** request ids · RFC 9457 adapter deferred to Phase 3 ·
storage default `seaweedfs` with `local` filesystem as zero-dependency fallback.

---

## 9. Verification already performed (2026-07-27)
- Maven Central returned **HTTP 200** for: Spring Boot 4.1.0 (+ Gradle plugin), Spring Modulith 2.1.0 BOM, Spring
  Framework 7.0.8, SpringDoc 3.0.3, DuckDB 1.5.5.0, MapStruct 1.6.3, ArchUnit 1.4.2, ShedLock 7.7.0,
  logstash-logback-encoder 9.0, testcontainers-keycloak 4.1.1, awssdk BOM 2.49.3, ulid-creator 5.2.3,
  flyway-database-postgresql 11.14.0.
- Toolchain on this machine: Temurin **JDK 25** present (no JDK 21) → the Gradle Java-21 toolchain will be
  auto-provisioned via the foojay resolver on first build. Gradle **9.6.1**, Docker **29.6.1**, Git **2.50.1** available.

---

## 10. Build-time findings (Phase 0/1, 2026-07-27)

Corrections discovered against the real Boot 4.1 artifacts — the catalog above reflects them:

1. **Boot 4 modularization**: several features left `spring-boot-autoconfigure`/`starter-test` —
   Flyway needs `spring-boot-starter-flyway`; `@AutoConfigureMockMvc`/`@WebMvcTest` live in
   `spring-boot-webmvc-test`; `TestRestTemplate` lives in `spring-boot-resttestclient`
   (+ needs `spring-boot-restclient`, and the bean is opt-in via `@AutoConfigureTestRestTemplate`).
2. **Testcontainers 2.0.5** (pinned by the Boot BOM): all modules renamed with a `testcontainers-`
   prefix (`testcontainers-postgresql`, `testcontainers-junit-jupiter`); the socket override is
   env-var-only (`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`, defaulted in `build.gradle.kts` for
   VM-based Docker like Colima/Docker Desktop).
3. **dasniko testcontainers-keycloak dropped** (was pinned 4.1.1): it depends on TC1-era
   `org.testcontainers:testcontainers`, which clashes with TC2's renamed artifacts. Keycloak
   integration tests run the official image via a plain TC2 `GenericContainer` instead.
4. **Gradle-native BOMs don't cross configurations**: `developmentOnly` needed its own
   `platform(boot-bom)` import.
5. **Jackson 3**: Boot 4.1's `ObjectMapper` is `tools.jackson.databind.ObjectMapper` (used by the
   security entry point/denied handler).

_End of plan. Phase 0 + Phase 1 are implemented and gated — see `docs/CHECKLIST.md` for live status._
