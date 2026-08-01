# Software Requirements Specification — `enterprise-modulith-template`

**Status:** current as of the working tree (2026-07-31). **Authority:** the code. Where an older
document in `docs/` disagrees with a statement here, the file cited here is the one that was read.
Every requirement names the file that implements it and the test that verifies it; where no
automated verification exists, the requirement says so explicitly.

**Scope of truth.** This document describes what is **built**. Anything planned is marked
**PLANNED** and confined to §9. Nothing planned appears in §3, §4 or §5.

---

## 1. Introduction

### 1.1 Purpose

`enterprise-modulith-template` is a production-shaped, multi-tenant Spring Modulith backend intended
to be **forked** as the starting point for a real service. Its purpose is not to be a feature
product but to be a *verified* set of answers to the cross-cutting problems every enterprise backend
must solve — identity, tenancy, authorization, auditability, idempotency, rate limiting, durable
async delivery, caching, soft deletion, observability — with each answer pinned by a test that runs
against real infrastructure.

This SRS states, in testable form, what the system does, what it guarantees, and how each guarantee
is verified, so that:

- a new engineer can determine the intended behaviour of any endpoint or rule without reading every class;
- an auditor can trace any control (tenant isolation, audit trail, retention, erasure) to code and to a test;
- a fork can tell what it inherits, what it must configure, and what it must build itself.

### 1.2 Scope

**In scope (built and verified):**

| Capability | Module package |
|---|---|
| Admin-provisioned identity over Keycloak (no just-in-time access) | `ug.co.smsone.identity` |
| Multi-tenant organizations projected from Keycloak Organizations, with permission-based org RBAC | `ug.co.smsone.organization` |
| Hierarchical platform administration roles | `ug.co.smsone.shared.security` |
| Platform settings and feature flags | `ug.co.smsone.settings` |
| Object storage with presigned access and namespace isolation | `ug.co.smsone.files` |
| Durable multi-channel notification delivery with a channel SPI | `ug.co.smsone.notification` |
| Per-organization signed outbound webhooks with a durable delivery queue | `ug.co.smsone.webhooks` |
| Append-only audit trail with who / when / where / what / from→to | `ug.co.smsone.audit` |
| Embedded OLAP reporting over DuckDB marts | `ug.co.smsone.analytics` |
| Cluster-safe scheduled jobs and retention purges | `ug.co.smsone.scheduler` |
| Envelope HTTP contract, cursor pagination, idempotency, rate limiting, two-level cache, soft delete | `ug.co.smsone.shared` |

**Explicitly out of scope of the running system** (each verified absent by grep over `src/`):

- **Audited impersonation** — no longer out of scope: implemented as slice C and specified in §3.14
  (FR-IMP-1..25). Listed here only because earlier revisions of this document called it planned.
- **Any user-facing delete of a setting or a feature flag** — the service methods exist
  (`SettingService.delete`, `FeatureFlagService.delete`), no HTTP route does. See §3.5 and §9.2.
- **Any delete of an organization or a user account at all** — neither a service method nor a route
  exists. `OrganizationService` has no `delete`, and no `.delete(`/`.deleteById(` call is made on
  `UserRepository` or `OrganizationRepository` anywhere in `src/main`. See §3.13 and §6.5.
- **CORS** — no `CorsConfigurationSource`, no `.cors(...)` in the filter chain, no `@CrossOrigin`
  anywhere in `src/main`. A browser SPA on a different origin must add it.
- **Kubernetes manifests and a container image** — there is no `Dockerfile` and no `deploy/`
  directory. The `${ENV:default}` configuration discipline that enables them is real and applied
  throughout, but nothing renders.
- **Custom metrics** — no `MeterRegistry`, `@Timed`, `@Counted` or `Observation` in `src/main`.
  Telemetry is entirely framework-produced.
- **A real SMS gateway** — `notification/internal/SmsChannelSender.java` is a logging stub,
  registered **by default** (`@ConditionalOnProperty(..., matchIfMissing = true)`).

### 1.3 Audience

| Reader | Start at |
|---|---|
| Engineer implementing a feature in a fork | §2 (module map), §3 (the capability being extended), `AGENTS.md` §1 |
| Reviewer / auditor | §5 (NFRs), §6 (data), §7 (verification), §8 (traceability) |
| Integrator writing a client | §4 (REST contract, endpoint catalogue, error codes) |
| Operator | §4.7 (integrations), §5.4 (reliability), §5.6 (observability), §6.2 (retention), §10 (every configuration key and its shipped default) |

### 1.4 Glossary

Terms are defined as the code uses them, not generically.

| Term | Definition in this system |
|---|---|
| **Tenant** | An organization. There is no other tenancy axis. The tenant key on the wire and in every table is the **Keycloak organization id** (`kc_org_id`), never the local surrogate primary key. |
| **Org** | The local projection of a Keycloak 26 Organization: `organization` table, `Organization` entity. Carries `kc_org_id`, `alias`, `name`, `status` (`ACTIVE` \| `SUSPENDED`). |
| **Subject** | The Keycloak user id — the JWT `sub` claim. The system's only durable identity. It is what `created_by`/`updated_by`, `audit_log.actor`, `membership.user_subject`, `in_app_notification.recipient`, idempotency principals and rate-limit buckets all key on. It is **never** `preferred_username`, which Keycloak allows to be renamed and, once freed, reassigned. |
| **Username** | `preferred_username`, exposed as `CurrentUser.username()` and used as the Spring principal name. Display only. Nothing durable may key on it (`AGENTS.md` §1). |
| **Platform role** | One of three hierarchical Keycloak **realm** roles — `platform-superadmin` > `platform-admin` > `platform-support` — declared as constants in `shared/security/PlatformRole.java`. Grants platform-wide authority. Grants **zero** organization permissions. |
| **Permission** | One of 15 fixed codes in the `organization.Permission` enum (`org:read` … `webhook:manage`). The organization authorization vocabulary. Roles are DB-editable bundles of permissions; every org-scoped authorization decision is made on a permission, never on a role code. |
| **Org role** | A named, DB-editable bundle of permissions inside one organization (`org_role` + `role_permission`). Exactly one role is seeded — `OWNER`, holding `EnumSet.allOf(Permission.class)`. All others are created by an owner. |
| **Provisioning** | The admin-driven creation of access: a Keycloak account (created if absent), a baseline realm role, temporary credentials delivered by Keycloak action-email, and a local `app_user` row in status `INVITED`. There is **no** just-in-time provisioning — a valid JWT is not access. |
| **Envelope** | The single JSON response shape: `{data, errors, meta, links}` with `data` XOR `errors`, `meta.requestId` always present. Applied by `shared/web/EnvelopeResponseBodyAdvice.java`; never hand-built in a controller. |
| **Cursor** | An opaque, base64url-encoded keyset position (`shared/web/Cursors.java`) encoding the sort keys of the last row of a page. Wire parameters are **`page[size]`** and **`page[after]`**. There are no offsets, no totals and no `COUNT` (ADR 0002). |
| **Idempotency key** | A client-supplied `Idempotency-Key` header on `POST`/`PUT`/`PATCH` under `/api/`. Claimed before the handler runs, scoped to `(principal, key)` where principal is the **subject**, and replayed verbatim on a duplicate (ADR 0005). |
| **Outbox** | The Spring Modulith JDBC event-publication registry (`event_publication`, `V2`). One row per *(event, registered listener)*; incomplete rows are republished on restart, giving at-least-once delivery. |
| **Inbox** | `event_inbox` (`V7`) + `shared/events/EventInbox.java`. A consumer-side dedupe table: `recordIfNew(listenerId, messageId)` is an `insert … on conflict do nothing` that returns whether this listener has seen this message. Turns at-least-once into effectively-once for the consumers that use it. |
| **Soft delete** | Deletion recorded, not executed. Seven aggregate tables carry `deleted_at` (`V17`); each entity declares `@SQLDelete` + `@SQLRestriction("deleted_at is null")`; every unique constraint on those tables became a **partial** unique index over live rows. A deleted row is invisible to every JPA read path and reachable only through `shared/persistence/SoftDeleteRecovery.java`. |
| **Purge** | The irreversible hard delete of soft-deleted rows past `app.persistence.soft-delete.retention` (default `P30D`), performed nightly by `scheduler/internal/SoftDeletePurgeJob.java`. |

### 1.5 References

**Architecture decision records** (`docs/adr/`) — all Accepted, all dated 2026-07-27:

| ADR | Decision |
|---|---|
| [0001](adr/0001-platform-baseline.md) | Platform baseline: Boot 4.1.0, Modulith 2.1.0, Java 21, Gradle 9.6.1 Kotlin DSL + version catalog, Valkey (BSD) not Redis, SeaweedFS + AWS S3 v2 SDK, embedded DuckDB, envelope contract, YAML config, Compose→Kubernetes. Rejects Lombok, Zalando problem-spring-web, Togglz, MinIO SDK, JSON:API libraries. |
| [0002](adr/0002-cursor-pagination.md) | Cursor (keyset) pagination only: `page[size]` ≤ 100 and `page[after]`; no `page[number]`, no totals, no `COUNT`. |
| [0003](adr/0003-testcontainers-only.md) | Real containers for every infra-touching test: Postgres 18, Keycloak 26, SeaweedFS, Valkey 8. No H2, no embedded substitutes, no mocked repositories. |
| [0004](adr/0004-two-level-cache.md) | Caffeine L1 + Valkey L2 with pub/sub L1 invalidation; L2 failure degrades to L1-only; 2 s Lettuce timeouts so an outage degrades rather than stalls. |
| [0005](adr/0005-idempotency-keys.md) | Per-principal idempotency keys, claim-first with a takeover lease; a global key namespace was rejected as a cross-account disclosure. |
| [0006](adr/0006-embedded-duckdb.md) | Embedded DuckDB behind the `AnalyticsEngine` seam; UTC marts, exact decimals, atomic staging-table swap. |

**Other repository documents:**

| Document | Relationship to this SRS |
|---|---|
| `AGENTS.md` | The engineering standard. §1 is the enforced-rule table; §14 the pre-merge checklist. Accurate; used here as a statement of intent, with every fact re-verified against source. |
| `docs/ARCHITECTURE.md` | Narrative overview: module map, the filter-ordered request path, and the cross-cutting-contract table. All ten modules are present; `docs/modulith/components.puml` (generated) remains the authority. |
| `docs/EVENTS.md` | Event catalogue. Accurate on payloads and triggers; see §3.12 for three omissions found against source. |
| `docs/openapi/openapi.{json,yaml}` | The exported OpenAPI 3.1.0 spec, refreshed by `./gradlew exportOpenApi` and also by the ordinary build. See §4.8 for its known divergences from the running contract. |
| `docs/modulith/` | Generated module canvases and PlantUML diagrams (`./gradlew exportModulithDocs`). Authoritative for the module list. |
| `docs/archive/PLATFORM_RBAC_IMPERSONATION_PLAN.md` | All three slices shipped (§3.3, §3.2, §3.14). The plan predates soft delete: its migration numbering (V17/V18) is superseded — impersonation landed as **V18 and V19**, and the plan's design is superseded wherever the two differ. |
| `docs/NEXT_TASKS.md` | **Retired 2026-08-01** (deleted). Its live entries — CI verification, the Kubernetes migration, event externalization, and the deferred rate-limit/notification hardening — currently have no backlog home. |
| `docs/CHECKLIST.md` | Gate ledger, one slice per shipped deliverable with the test that proves each gate. |
| `docs/LOCAL_ACCESS.md` | Local access guide. Documents the author's gitignored port overrides, not the defaults — that is its one remaining caveat. Its worked example now mints `AUDITOR` before inviting into it (the `"roleCode":"MEMBER"` version was stale after V16), and it carries the three impersonation routes, the `X-Impersonate` header and a worked session (§3.14). |
| `docs/DATA_MODEL.md` | The data-model reference: storage topology, entity hierarchy, per-module table-and-column tables for all 17 tables, the soft-delete mechanism, migration history and retention. Written against the same working tree as this SRS. §6.1 points at it rather than restating it. |

---

## 2. Overall description

### 2.1 Product perspective — a modular monolith

One Gradle module, one deployable, one database. Modules are Java packages under `ug.co.smsone`,
declared with `@ApplicationModule` in a `package-info.java`, and the boundaries are enforced by tests
rather than by build-time separation. ADR 0001 records that physical splitting is a deliberate later
step — *which is exactly why the logical boundaries are enforced now*.

`Application.java` is `@Modulithic @SpringBootApplication`.

### 2.2 Module map

Twelve modules. Verified from the `package-info.java` files and `docs/modulith/components.puml`.

| Module | Display name | Owns (tables) | Public API package contains |
|---|---|---|---|
| `shared` | Shared Kernel — **the only `ApplicationModule.Type.OPEN` module** | `idempotency_key`, `event_inbox` | envelope + error types, security (`CurrentUser`, `PlatformRole`, the `OrgAuthorization` and `ImpersonationLookup` ports, `ImpersonatedPrincipal`), persistence bases, `AuditLog` port, `EventInbox`, cache, rate limiting, `SafeOutboundUrl` |
| `identity` | Identity | `app_user`, `impersonation_session` | `UserProvisioning`, `UserDirectory`, `ProvisioningStatus` |
| `organization` | Organization | `organization`, `org_role`, `role_permission`, `membership` | `Permission`, `OrganizationRegistered`, `OrganizationStatusChanged`, `MembershipCreated`, `MembershipRoleChanged`, `MemberRemoved`, `RolePermissionsChanged` |
| `settings` | Settings | `setting`, `feature_flag` | `SettingChanged`, `FeatureFlagChanged` |
| `localization` | Localization | `translation` | `Messages` port, `TranslationChanged` |
| `search` | Search | `search_document` | `SearchIndex` port, `SearchDoc` |
| `audit` | Audit | `audit_log` | (none — consumed through the `shared.audit.AuditLog` port) |
| `notification` | Notification | `in_app_notification`, `notification_delivery` | `Notifications`, `NotificationChannelSender` (SPI), `NotificationChannel`, `NotificationMessage`, `NotificationRequest`, `Recipient` |
| `webhooks` | Webhooks | `webhook_subscription`, `webhook_delivery` | (none — the module is a pure consumer + REST surface) |
| `files` | Files | none (S3 object storage) | `FileStorageProvider`, `FileStorageException`, `FileNotFoundException` |
| `analytics` | Analytics | none in Postgres (DuckDB marts + Parquet) | `AnalyticsEngine`, `AnalyticsException` |
| `scheduler` | Scheduler | none (consumes `shedlock`) | (none) |

Seventeen of the schema's twenty tables appear above. The remaining three are **framework-owned and
belong to no module**: `event_publication` (Spring Modulith's JDBC registry, `V2`), `shedlock`
(ShedLock, `V4`) and `flyway_schema_history` (created by Flyway itself, in no migration). They are
read and written by libraries, not by application code, and `V17` deliberately leaves all three out of
soft delete. The full table→column inventory is in `docs/DATA_MODEL.md` §4; §6.1 summarises it.

**Dependency direction** (`AGENTS.md` §2.2, enforced by `ModularityTests`):

```
business module ──► shared (OPEN kernel)
business module ──► another module's API package only, never its internal package
shared          ──► nothing in a business module (compile time)
```

Where `shared` needs behaviour a business module owns, it declares a **port** and default-denies or
no-ops when no implementation is present. Three such ports exist:

- `shared.security.OrgAuthorization` → `organization.internal.OrgAuthorizationImpl`. Resolved through
  an `ObjectProvider`; `ApiPermissionEvaluator.hasPermission` returns `false` when it is absent
  (`shared/security/ApiPermissionEvaluator.java:41-44`).
- `shared.security.ImpersonationLookup` → `identity.internal.ImpersonationLookupImpl`. Same
  `ObjectProvider` shape; `ImpersonationFilter` **denies** the header when no implementation is
  present rather than ignoring it (`shared/security/ImpersonationFilter.java:103-107`). This is the
  port that lets the enforcing filter live in `shared` while the session table lives in `identity`
  (§3.14).
- `shared.audit.AuditLog` → `audit.internal.AuditLogImpl` (`@Primary`), with a `@ConditionalOnMissingBean`
  no-op fallback in `shared/audit/AuditLogConfiguration.java` so modules that audit still boot inside
  single-module Modulith slice tests.

**Integration style.** Modules talk over domain events (§3.12) and ports. There are exactly three
foreign keys in the whole schema, all intra-module: `role_permission.role_id → org_role(id)`
(cascade), `membership.role_id → org_role(id)` (no cascade), `webhook_delivery.subscription_id →
webhook_subscription(id)` (cascade). Every cross-module link — `org_id`, `user_subject`, `actor`,
`recipient`, `created_by` — is a bare column holding a Keycloak identifier, deliberately (`AGENTS.md`
§1, §2.4.3).

### 2.3 User classes and characteristics

Six classes. The first three sit on the **platform** axis, the next two on the **organization** axis;
the axes are disjoint (§5.1.1).

| Class | Credential shape | What it can reach | What it cannot reach |
|---|---|---|---|
| **Platform superadmin** (`platform-superadmin`) | Keycloak realm role | Everything `platform-admin` can, by hierarchy | **No organization permission whatsoever.** Today it has **no exclusive endpoint** — `hasRole('platform-superadmin')` appears nowhere in `src/main`. Its one reserved power is live: only this tier may open an impersonation session against an account that itself holds a platform realm role (§3.14, FR-IMP-14). |
| **Platform admin** (`platform-admin`) | Keycloak realm role | Org lifecycle (create / suspend / reactivate), settings and feature-flag writes, cross-namespace file delete, plus everything support can | No organization permission |
| **Platform support** (`platform-support`) | Keycloak realm role | Platform user listing, platform audit trail, scheduler locks, analytics reports, cross-namespace file **read** | No writes to platform state; no organization permission |
| **Org owner** | Membership on the seeded `OWNER` role | Every permission in the catalog, inside **one** organization — the org its token is scoped to | Anything platform-tier; anything in another organization |
| **Org member on a custom role** | Membership on an owner-created role | Exactly the permissions in that role's bundle, inside its own organization. The role's **code is inert**: no request path branches on it (the only code the application names is `OWNER`, and only for the last-owner guard and first-owner attachment) | Any permission not in its bundle; may not grant a permission it does not itself hold (§3.2, FR-ORG-19) |
| **Unprovisioned subject** | A valid Keycloak JWT with no `app_user` row | `GET /api/v1/me` only, which reports `provisioningStatus: "UNPROVISIONED"` | Every other `/api/**` path → **403 `ACCOUNT_NOT_PROVISIONED`** |
| **Service account** | `smsone-admin` client-credentials token | Nothing in this API. The client deliberately lacks the `smsone-api-audience` client scope, so its token fails audience validation. It exists solely so the application can call the Keycloak Admin API as a client, not so it can call itself | The whole API surface |

A **disabled** subject (`app_user.status = DISABLED`, or a soft-deleted `app_user` row) is denied on
every path including `/api/v1/me` → **403 `ACCOUNT_DISABLED`**
(`identity/internal/UserAccessService.java:50-67`).

### 2.4 Operating environment

| Dependency | Version pinned | Role | Local provisioning |
|---|---|---|---|
| Java | 21 (toolchain) | runtime | foojay resolver |
| Spring Boot | 4.1.0 | framework | — |
| Spring Modulith | 2.1.0 | module verification, event registry, docs | — |
| PostgreSQL | 18 (`postgres:18.4-alpine`) | system of record; Flyway-owned schema | Compose `postgres` |
| Keycloak | 26.7.0 | OIDC issuer, user + organization store, Admin API | Compose `keycloak`, realm `smsone` imported from `docker/keycloak/realm-smsone.json` |
| Valkey | 8 (`valkey/valkey:8-alpine`) | cache L2, cache-invalidation pub/sub, rate-limit buckets | Compose `valkey` |
| SeaweedFS | 4.40 | S3-compatible object storage | Compose `seaweedfs` |
| SMTP | Mailpit v1.30.2 locally | email channel | Compose `mailpit` |
| OTLP collector | `grafana/otel-lgtm:0.28.0` | traces/metrics/logs sink | Compose `otel-lgtm` |
| DuckDB | 1.5.5.0 | embedded OLAP | in-process, no container |

Every infrastructure coordinate is externalised as `${ENV:default}` in
`src/main/resources/application.yaml`; `docker/.env.example` carries the same variable names. Four
Compose services have **no healthcheck** (`keycloak`, `seaweedfs`, `mailpit`, `otel-lgtm`) — only
`postgres` and `valkey` do, so Boot's Docker Compose support treats the other four as ready as soon
as they start.

### 2.5 Design and implementation constraints

| Constraint | Source | Consequence |
|---|---|---|
| Flyway owns the schema; `spring.jpa.hibernate.ddl-auto: validate` | `application.yaml:27-29` | No DDL outside `db/migration`. There is no `schema.sql` and no test-only DDL anywhere. |
| Migrations are forward-only and numbered; **V1–V19 exist, next free is V20** | `db/migration/`, `AGENTS.md` §4.5 | V17 is soft delete, V18 `impersonation_session`, V19 the `audit_log` impersonation columns. Any plan citing V17 or V18 for new work is stale — `archive/PLATFORM_RBAC_IMPERSONATION_PLAN.md` §4 is the one that was. |
| No cross-module foreign keys | `AGENTS.md` §1 | Referential integrity across modules is an application concern; `SoftDeletePurgeJob` states the consequence for purge ordering (`SoftDeletePurgeJob.java:49-53`). |
| `spring.jpa.open-in-view: false` | `application.yaml:30` | No lazy loading past the service boundary. |
| No Lombok; records + constructor injection | ADR 0001, `ArchitectureTests.noFieldInjection` | Enforced by ArchUnit. |
| Every infra-touching test uses a real container | ADR 0003 | The suite requires Docker. |
| Cursor pagination only | ADR 0002 | Every listable collection needs a stable unique sort; all are `createdAt desc, id desc`. |
| Envelope on every JSON response | `AGENTS.md` §1 | Controllers return `ResourceObject` / `WindowedResult`, never `ApiResponse`. |
| The application never handles a password | `identity/internal/KeycloakUserAdminGateway.java:110-114` | Credentials are set by Keycloak's `execute-actions-email` flow only; see FR-IDN-5 and §6.4. |

### 2.6 Assumptions and dependencies

1. **Keycloak is the sole identity authority.** The application stores no credential, no password
   hash and no refresh token. Account existence, authentication and organization membership at the
   IdP are Keycloak's; the application keeps a local projection and its own authorization data.
2. **Tokens are minted for this API.** `spring.security.oauth2.resourceserver.jwt.audiences` is set
   (`smsone-api`), and the `smsone-web` client carries the `smsone-api-audience` client scope that
   satisfies it. Without this, any realm token — including the `smsone-admin` service account's,
   which holds `realm-management` roles — would authenticate here.
3. **A token is scoped to at most one organization.** `CurrentUserProvider.resolveActiveOrg`
   (`:74-89`) returns no active org unless the Keycloak `organization` claim has **exactly one**
   entry. A zero- or multi-org token holds no org permission.
4. **The Keycloak realm export ships the built-in client scopes.** A realm export that declares
   `clientScopes` *replaces* the built-ins; dropping `basic`/`roles` would mint tokens with no `sub`
   and no `realm_access` — accepted by the resource server, then authorizing as nobody.
   `KeycloakIntegrationTest.realmMintsTokensCarryingSubjectAndRealmRoles` exists as the guard.
5. **Postgres `now()` and the JVM `Clock` bean are different clocks.** `created_at`/`updated_at` come
   from `shared/config/ClockConfig`; `deleted_at` on the `@SQLDelete` path comes from Postgres
   `now()`. A test that fixes the `Clock` cannot control `deleted_at`.
6. **Egress is not confined by the application alone.** `shared/http/SafeOutboundUrl` blocks
   private, loopback, link-local, CGNAT, ULA and NAT64-embedded addresses at both configure time and
   send time, but its own javadoc documents the residual DNS-rebinding window and requires a network
   egress policy in any real deployment.

---

## 3. Functional requirements

**Reading the tables.** Each requirement has a stable ID, a testable SHALL statement, the file that
implements it, and the test that verifies it. Paths are repo-relative; `src/main/java/ug/co/smsone/`
and `src/test/java/ug/co/smsone/` are elided as `main:` and `test:`. A verification cell of
**"none"** means no automated test covers that requirement — those are collected in §7.5.

### 3.1 Request pipeline and API contract (FR-HTTP)

Every `/api/**` request passes five gates before a handler runs. Order is taken from the `@Order`
annotations; Spring Security's chain is registered by Boot at −100.

| Order | Component | Effect |
|---|---|---|
| `HIGHEST_PRECEDENCE` | `RequestIdFilter` | MDC + `X-Request-Id` response header |
| −100 | Spring Security chain | `anyRequest().authenticated()` → 401 |
| −2 | `ImpersonationFilter` | only when `X-Impersonate` is present: swaps the principal to the session's target, or 403 `FORBIDDEN`. First after authentication so the whole request downstream sees ONE effective principal |
| −1 | `RateLimitFilter` | 429 `RATE_LIMITED` + `Retry-After` |
| 0 | `IdempotencyFilter` | only when `Idempotency-Key` is present |
| 1 | `ProvisioningGateFilter` | 403 `ACCOUNT_NOT_PROVISIONED` / `ACCOUNT_DISABLED`. Under impersonation it evaluates the target and does **not** lazily activate them |

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-HTTP-1 | wrap every JSON response body from `ug.co.smsone` in the envelope `{data, errors, meta, links}` with `data` XOR `errors`, omitting the null branch entirely (`spring.jackson.default-property-inclusion: non_null`) | `main:shared/web/EnvelopeResponseBodyAdvice.java` | `test:shared/web/EnvelopeContractTest.wrapsSuccessInEnvelopeWithRequestId` |
| FR-HTTP-2 | populate `meta.requestId` on every response, falling back to the literal `"unknown"` only when the MDC is empty, and `meta.apiVersion` to `"1"` | `main:shared/web/ApiMetaFactory.java:11,25` | `test:shared/web/EnvelopeContractTest.wrapsSuccessInEnvelopeWithRequestId` |
| FR-HTTP-3 | accept an inbound `X-Request-Id`, else `X-Correlation-Id`, only when it matches `^[A-Za-z0-9_-]{1,64}$`, and otherwise mint a ULID | `main:shared/web/RequestIdFilter.java` | `test:shared/web/EnvelopeContractTest.echoesValidInboundRequestId`, `.replacesMalformedInboundRequestId` |
| FR-HTTP-4 | set the `X-Request-Id` response header on **every** response, including errors produced outside MVC | `main:shared/web/RequestIdFilter.java` | `test:shared/security/SecurityContractTest.unauthenticatedRequestGets401Envelope` |
| FR-HTTP-5 | emit one `errors[]` entry per field violation on bean-validation failure, with `source.pointer = /data/attributes/<field>` and code `VALIDATION_<CONSTRAINT>`, overall HTTP 422 | `main:shared/error/GlobalExceptionHandler.java:49-65` | `test:settings/SettingsApiIntegrationTest.blankValueYields422MultiErrorEnvelope`, `test:shared/web/EnvelopeContractTest.returnsMultiErrorEnvelopeForValidationFailure` |
| FR-HTTP-6 | render an `ApiException` as a single error carrying its own `ErrorCode`, curated detail and optional `ApiSource` | `main:shared/error/GlobalExceptionHandler.java:83-89` | `test:shared/web/EnvelopeContractTest.translatesBusinessExceptionToEnvelope` |
| FR-HTTP-7 | never place a stack trace, framework exception message, exception class name or binding detail on the wire, and log the stack trace only in the catch-all handler | `main:shared/error/GlobalExceptionHandler.java:119-126`; `application.yaml:71-77` (`server.error.include-*: never`) | `test:shared/web/EnvelopeContractTest.neverLeaksStackTracesOrInternalDetails`, `test:shared/error/ProblemDetailContractTest.catchAll500NeverLeaksInProblemJsonEither` |
| FR-HTTP-8 | return RFC 9457 `application/problem+json` when the request's `Accept` header contains that media type, carrying `title`, `detail`, and the extensions `code` and `requestId` | `main:shared/error/GlobalExceptionHandler.java:140-162`; `main:shared/web/EnvelopeErrorWriter.java:30-59` | `test:shared/error/ProblemDetailContractTest` (all six tests) |
| FR-HTTP-9 | include the full `errors` list as a problem+json extension when there is more than one error, or exactly one error that carries a `source` | `main:shared/error/GlobalExceptionHandler.java:140-152` | `test:shared/error/ProblemDetailContractTest.validationProblemCarriesErrorsExtension`, `.singleValidationErrorKeepsItsSourcePointer` |
| FR-HTTP-10 | reject an unauthenticated `/api/**` request with **401 `UNAUTHORIZED`** in the envelope | `main:shared/security/ApiAuthenticationEntryPoint.java` | `test:shared/security/SecurityContractTest.unauthenticatedRequestGets401Envelope`, `test:shared/security/KeycloakIntegrationTest.garbageTokenIsRejectedWithEnvelope` |
| FR-HTTP-11 | render a method-security denial as **403 `FORBIDDEN`** with the detail "You do not have permission to perform this operation." | `main:shared/error/GlobalExceptionHandler.java:93-100` | `test:shared/security/PlatformRoleHierarchyTest.forbiddenIsRenderedAsTheEnvelope` |
| FR-HTTP-12 | read cursor page parameters from the literal names **`page[size]`** and **`page[after]`** only | `main:shared/web/CursorPageRequestArgumentResolver.java:15-16` | `test:shared/web/CursorPaginationContractTest.theBracketedNamesAreWhatTheServerHonours` |
| FR-HTTP-13 | default `page[size]` to 20, **reject** (never clamp) a value below 1 or above 100 with 422 and `source.parameter = "page[size]"`, and reject a non-integer with 422 | `main:shared/web/CursorPageRequest.java:16-17`; `main:shared/web/CursorPageRequestArgumentResolver.java` | `test:shared/web/CursorPaginationContractTest.theDocumentedBoundsAreTheOnesTheResolverEnforces` |
| FR-HTTP-14 | reject an undecodable or foreign cursor with 422 and `source.parameter = "page[after]"` rather than a 500 | `main:shared/web/Cursors.java`; `main:shared/web/CursorPageRequest.java:28-40` | `test:settings/SettingsApiIntegrationTest.invalidCursorYields422` |
| FR-HTTP-15 | report pagination state as `meta.page {size, count, hasMore, nextCursor}` with **no totals and no `COUNT` query**, and set `nextCursor` only when the window has a next page and is non-empty | `main:shared/web/WindowedResult.java`; `main:shared/web/PageMeta.java` | `test:settings/SettingsApiIntegrationTest.cursorPaginationWalksTheCollection` |
| FR-HTTP-16 | emit `links.next` as `<path>?page[size]=<n>&page[after]=<cursor>` when a next page exists, and `links.self` as the request path | `main:shared/web/EnvelopeResponseBodyAdvice.java:42-48` | `test:settings/SettingsApiIntegrationTest.cursorPaginationWalksTheCollection` |
| FR-HTTP-17 | apply idempotency **only** to `POST`/`PUT`/`PATCH` requests under `/api/` that carry an `Idempotency-Key` header — per-request opt-in, no annotation and no allow-list; `DELETE` is excluded | `main:shared/idempotency/IdempotencyFilter.java:43,62-67` | `test:shared/idempotency/IdempotencyIntegrationTest.distinctKeysExecuteIndependently` |
| FR-HTTP-18 | reject an `Idempotency-Key` not matching `^[A-Za-z0-9_-]{1,128}$` with 422 and `source.header = "Idempotency-Key"` | `main:shared/idempotency/IdempotencyFilter.java` | `test:shared/idempotency/IdempotencyIntegrationTest.malformedKeyIsRejected` |
| FR-HTTP-19 | reject a request body larger than `app.idempotency.max-body-bytes` (default 262144) with **413 `PAYLOAD_TOO_LARGE`** without buffering it | `main:shared/idempotency/IdempotencyFilter.java:54` | `test:shared/idempotency/IdempotencyIntegrationTest.oversizedBodyIsRejectedNotBuffered` |
| FR-HTTP-20 | scope idempotency keys to `(principal, key)` where **principal is the token subject** (`"anonymous"` off-request), so no user can replay or squat another's key | `main:shared/idempotency/IdempotencyFilter.java:156-165`; `V5__idempotency_key.sql` | `test:shared/idempotency/IdempotencyIntegrationTest.keysAreScopedPerPrincipal`; `test:shared/security/SubjectAttributionTest.twoAccountsSharingAUsernameDoNotShareIdempotencyKeys`, `.oneAccountKeepsItsIdempotencyKeysAcrossARename` |
| FR-HTTP-21 | claim the key **before** invoking the handler, replay the stored status/content-type/body with `Idempotency-Replayed: true` on a duplicate, and never re-execute | `main:shared/idempotency/IdempotencyFilter.java`; `main:shared/idempotency/IdempotencyStore.java` | `test:shared/idempotency/IdempotencyIntegrationTest.duplicateRequestIsReplayedWithoutReexecuting` |
| FR-HTTP-22 | reject a reused key whose request fingerprint (SHA-256 of method + URI + body) differs, with 409 `CONFLICT` | `main:shared/idempotency/IdempotencyFilter.java:122-146,167` | `test:shared/idempotency/IdempotencyIntegrationTest.sameKeyDifferentPayloadConflicts` |
| FR-HTTP-23 | store only responses with status `< 400`, releasing the key on any 4xx/5xx or thrown exception so errors stay retryable | `main:shared/idempotency/IdempotencyFilter.java:106-113` | `test:shared/idempotency/IdempotencyIntegrationTest.errorResponsesAreNotStored` |
| FR-HTTP-24 | allow an in-progress claim older than `app.idempotency.in-progress-lease` (default PT5M) to be taken over, while a completed row is never taken over | `main:shared/idempotency/IdempotencyStore.java` (`on conflict … where response_status is null and created_at < ?`) | none (lease expiry is not exercised) |
| FR-HTTP-25 | rate-limit `/api` and `/api/**` on every method except `OPTIONS`, matching the first configured tier by path and method and falling through to the default tier | `main:shared/ratelimit/RateLimitFilter.java:28,49`; `main:shared/ratelimit/RateLimitProperties.java:42-49` | `test:shared/ratelimit/RateLimitIntegrationTest.edgeFilterReturns429WithEnvelopeAndHeaders` |
| FR-HTTP-26 | offer exactly three bucket scopes — **`IP`, `PRINCIPAL`, `TENANT`** (`main:shared/ratelimit/RateLimitScope.java:8-10`) — and degrade along a fixed chain: TENANT keys on the caller's **active organization id**, falling back to a configured flat tenant claim, then the **subject**, then the client IP; PRINCIPAL keys on the subject, falling back to IP; IP keys on the client IP directly. Never the username. Only TENANT and PRINCIPAL appear among the shipped tiers; `IP` is settable on any custom `app.rate-limit.tiers[].scope` and is implemented — a tier whose scope is neither TENANT nor PRINCIPAL falls straight through to a pure per-IP bucket | `main:shared/ratelimit/RateLimitKeyResolver.java:32-52`; `main:shared/ratelimit/RateLimitScope.java` | `test:shared/ratelimit/RateLimitIntegrationTest.edgeFilterKeysByActiveOrgFromTheOrganizationClaim`, `.principalFallbackKeysBySubjectNotUsername`; the `IP` scope itself has **no test** |
| FR-HTTP-27 | consult `X-Forwarded-For` only when `app.rate-limit.trust-forwarded-for` is true (default **false**) and then take the **rightmost** hop, capped at 45 characters | `main:shared/ratelimit/RateLimitKeyResolver.java:87-101` | none |
| FR-HTTP-28 | set `RateLimit-Policy`, `RateLimit` and legacy `X-RateLimit-Limit`/`-Remaining`/`-Reset` on every rate-limited response, allowed or denied, and add `Retry-After` on denial | `main:shared/ratelimit/RateLimitFilter.java:76-84` | `test:shared/ratelimit/RateLimitIntegrationTest.edgeFilterReturns429WithEnvelopeAndHeaders` |
| FR-HTTP-29 | deny with **429 `RATE_LIMITED`** in the envelope, naming the tier, once a bucket is exhausted | `main:shared/ratelimit/RateLimitFilter.java` | `test:shared/ratelimit/RateLimitIntegrationTest.distributedBucketAllowsUpToCapacityThenDeniesPerKey` |
| FR-HTTP-30 | **fail open** (allow) when the rate-limit backend is unavailable, unless the matched tier sets `fail-closed`; no configured tier does | `main:shared/ratelimit/DistributedRateLimiter.java:103-107,152` | none (fail-open path is not asserted) |
| FR-HTTP-31 | expose only `/actuator/health/**`, `/actuator/info`, `/v3/api-docs*`, `/swagger-ui/**` and `/swagger-ui.html` without authentication; everything else requires a token | `main:shared/security/SecurityConfig.java:36-39` | `test:shared/security/SecurityContractTest.healthProbesArePublic`, `.unauthenticatedRequestGets401Envelope` |
| FR-HTTP-32 | run stateless with CSRF disabled and no HTTP session | `main:shared/security/SecurityConfig.java:33-34` | none (structural) |

### 3.2 Identity and provisioning (FR-IDN)

The governing rule is **no just-in-time provisioning**: a valid Keycloak JWT is not access.

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-IDN-1 | reject an authenticated request to `/api/**` from a subject with no `app_user` row with **403 `ACCOUNT_NOT_PROVISIONED`** | `main:identity/internal/ProvisioningGateFilter.java:78-84` | `test:identity/internal/ImpersonationProvisioningGateTest.anUnprovisionedSubjectIsRefusedByTheGateInThisContext` — the **only** HTTP-level assertion of the gate in the suite, and it exists because §3.14's tests are the only ones that re-enable `app.provisioning.gate-enabled`. `test:identity/internal/IdentityProvisioningTest.gateDeniesUnprovisionedThenActivatesInvitedOnFirstHit` covers the `UserAccessService` decision underneath it |
| FR-IDN-2 | exempt **only** `GET /api/v1/me` from the provisioning gate, matching on method **and** exact path, and treat `NOT_PROVISIONED` as allowed there | `main:identity/internal/ProvisioningGateFilter.java:34,62-66,87-89` | **none** — no test references `/api/v1/me` at all, and no test instantiates `ProvisioningGateFilter` (§7.5) |
| FR-IDN-3 | deny a `DISABLED` account on **every** path including `/api/v1/me`, with **403 `ACCOUNT_DISABLED`** | `main:identity/internal/ProvisioningGateFilter.java:82-83`; `main:identity/internal/UserAccessService.java:34,55` | **decision level only** — `test:identity/internal/IdentityProvisioningTest.disabledUserIsDeniedByBothAuthorizeAndPeek` asserts both `authorize` and `peek` return `DISABLED`; the HTTP 403 and its code are **unverified** (§7.5) |
| FR-IDN-4 | treat a **soft-deleted** `app_user` as `DISABLED`, not as unprovisioned, so a deletion never reads as an invitation | `main:identity/internal/UserAccessService.java:58-67`; `main:identity/internal/UserRepository.java:21-23` (native `existsDeletedBySubject`, because `@SQLRestriction` hides the row) | `test:analytics/internal/AnalyticsApiTest.softDeletedUsersAreExcludedFromTheReport` covers the read side; the gate branch itself has **no test** |
| FR-IDN-5 | provision a user by creating the Keycloak account if absent, granting the configured baseline realm role, issuing temporary credentials via Keycloak's `execute-actions-email` (`UPDATE_PASSWORD`, `VERIFY_EMAIL`) when the account has none, and recording a local `app_user` in status `INVITED` | `main:identity/internal/UserProvisioningService.java:41-65`; `main:identity/internal/KeycloakUserAdminGateway.java` | `test:identity/internal/IdentityProvisioningTest.provisionsKeycloakUserAndRecordsInvitedRow`; `test:identity/internal/KeycloakProvisioningIntegrationTest.provisioningANewUserInvitesThemAndTheEmailReachesTheMailbox` (real Keycloak + real Mailpit) |
| FR-IDN-6 | **never** issue or store a password itself — the action-email is the only credential path, and no credential value is ever visible to the application or to the inviting admin | `main:identity/internal/KeycloakUserAdminGateway.java:110-114` | `test:identity/internal/KeycloakProvisioningIntegrationTest.provisioningANewUserInvitesThemAndTheEmailReachesTheMailbox` |
| FR-IDN-7 | **refuse to start** when `app.provisioning.default-realm-role` names a platform role, because invite is reachable by any org member holding `member:invite` | `main:identity/internal/ProvisioningProperties.java:19-32` | `test:identity/internal/ProvisioningPropertiesTest.aPlatformRoleAsTheProvisioningBaselineIsRejected`, `.anOrdinaryRoleIsAccepted`, `.blankOrMissingGrantsNothing`, `.aLookalikeRoleIsNotMistakenForAPlatformRole` |
| FR-IDN-8 | grant provisioned accounts the baseline realm role only (default `USER`) and no platform authority | `main:identity/internal/UserProvisioningService.java:52-54` (the grant itself; 47-51 is the comment block explaining the ordering) | `test:identity/internal/KeycloakProvisioningIntegrationTest.provisioningGrantsTheBaselineRealmRoleAndNoPlatformAuthority` |
| FR-IDN-9 | be idempotent across re-provisioning: an existing Keycloak account with credentials receives no new invite, and a concurrent duplicate insert is absorbed when a row for that subject now exists | `main:identity/internal/UserProvisioningService.java:41-76` | `test:identity/internal/IdentityProvisioningTest.preExistingKeycloakAccountWithCredentialsGetsNoInvite`, `.fullyProvisionedUserIsIdempotentAndSendsNoInvite`, `.retryAfterFailedInviteReissuesCredentials` |
| FR-IDN-10 | activate an `INVITED` account to `ACTIVE` lazily on its first real API request, publishing `UserActivated`, and treat a lost activation race as allowed rather than a 500 — **the person's own** request only, never an operator's read of them (§3.14, FR-IMP-12) | `main:identity/internal/UserAccessService.java:28-47`; `main:identity/internal/User.java:56-60`; `main:identity/internal/ProvisioningGateFilter.java:96-104` | `test:identity/internal/IdentityProvisioningTest.gateDeniesUnprovisionedThenActivatesInvitedOnFirstHit`; over HTTP, `test:identity/internal/ImpersonationProvisioningGateTest.aUsersOwnFirstRequestStillActivatesThem` ↔ `.aSessionNeverActivatesTheTargetItWears` |
| FR-IDN-11 | expose `GET /api/v1/me` to any authenticated caller, reporting subject, email, hierarchy-expanded roles, active org alias and id, and a provisioning status of `INVITED`/`ACTIVE`/`DISABLED` or the literal `"UNPROVISIONED"` | `main:identity/internal/MeController.java:29-35` | none (no dedicated test; exercised indirectly by the gate test) |
| FR-IDN-12 | expose a platform-wide, cursor-paginated user listing at `GET /api/v1/admin/users` requiring `platform-support` | `main:identity/internal/UserAdminController.java:29-34` | `test:shared/security/PlatformRoleHierarchyTest` (all tier cases use this endpoint as the support floor) |
| FR-IDN-13 | resolve a subject from an email address for cross-module use, case-insensitively and deterministically (earliest `provisioned_at` wins) | `main:identity/internal/UserDirectoryService.java:18-24` | `test:notification/NotificationDeliveryTest.flagToggleEnqueuesThenWorkerDeliversEmailAndInApp` |
| FR-IDN-14 | provide an opt-in, off-by-default **identity** dev bootstrap that projects an **existing** Keycloak account into `app_user` and can never create a Keycloak account, so a realm-imported platform admin passes the no-JIT gate without owning an org; idempotent, and needing Keycloak reachable | `main:identity/internal/PlatformAdminBootstrap.java:44-56` (`@ConditionalOnProperty(havingValue = "true")`, no `matchIfMissing`); `main:identity/internal/IdentityDevBootstrapProperties.java`; properties `app.identity.dev-bootstrap.{enabled, email}` (defaults `false`, `ayesigapo@gmail.com` — `application.yaml:95-101`) | none |
| FR-IDN-15 | reconcile `app_user` against Keycloak on a schedule, disabling rows whose Keycloak account is definitively gone, because Keycloak is the system of record and pushes no deletion to this projection | `main:identity/internal/IdentityReconciliationJob.java:70-119` (`@Scheduled` + `@SchedulerLock("identity-reconciliation")`, daily `0 0 2 * * *`) | `test:identity/internal/IdentityReconciliationJobTest.anAccountDeletedInKeycloakIsDisabledAndAudited`, `.anAccountStillInKeycloakIsUntouched` |
| FR-IDN-16 | report a Keycloak account lookup as **tri-state** (`PRESENT`/`ABSENT`/`UNKNOWN`) and never act on `UNKNOWN`, so one Keycloak outage can never be read as a mass deletion | `main:identity/internal/KeycloakUserAdminGateway.accountPresence`; `main:identity/internal/IdentityReconciliationJob.java:88-93` | `test:identity/internal/IdentityReconciliationJobTest.anInconclusiveLookupNeverRevokesAnybody` |
| FR-IDN-17 | abandon a reconciliation run, changing nothing, when the share of apparently-deleted accounts exceeds `max-orphan-ratio` — a wrong realm, base URL or lost `view-users` role makes every per-row lookup a legitimate 404, and only the proportion distinguishes that from attrition | `main:identity/internal/IdentityReconciliationJob.java:100-110` | `test:identity/internal/IdentityReconciliationJobTest.aMassDisappearanceIsTreatedAsMisconfigurationAndChangesNothing` |
| FR-IDN-18 | ship the job in `REPORT` mode, skip accounts inside `grace-period` (still possibly mid-provisioning), and never re-visit an already-`DISABLED` row | `main:identity/internal/IdentityReconciliationJob.java:78-81,148-152`; `main:identity/internal/IdentityReconciliationProperties.java` | `test:identity/internal/IdentityReconciliationJobTest.reportModeFindsTheOrphanWithoutRevokingIt`, `.anAccountInsideTheGracePeriodIsNotExamined`, `.anAlreadyDisabledAccountIsNotVisitedAgain` |
| FR-IDN-19 | audit each reconciliation-driven revocation as `identity.user_disabled_by_reconciliation` with a **null** actor — no human made the decision — committing the audit row in the same transaction as the status change | `main:identity/internal/IdentityReconciliationJob.java:140-160` (explicit `TransactionTemplate`: a self-invoked `@Transactional` never reaches the proxy) | `test:identity/internal/IdentityReconciliationJobTest.anAccountDeletedInKeycloakIsDisabledAndAudited` |

The "can never create a Keycloak account" claim is about the **identity** bootstrap only. The
**organization** dev bootstrap (FR-ORG-31) is a separate runner and does create Keycloak state.

**Absent by design / by gap:** there is **no endpoint that disables a user**. `User.disable()`
(`main:identity/internal/User.java:62-64`) has no caller in `src/main`; `ProvisioningStatus.DISABLED`
is fully enforced by the gate but can only be reached by direct SQL or by soft-deleting the row.
`KeycloakUserAdminGateway.realmRoles(String)` reads the target's **effective** realm roles (the
`/role-mappings/realm/composite` endpoint, so composite and group-derived roles are included, matching
what the token's `realm_access.roles` will carry) and is the input to the impersonation tier guardrail
(§3.14, FR-IMP-14).

### 3.3 Organizations and RBAC (FR-ORG)

**The governing rule: authorization inside an organization is decided on a permission, never on a
role code.** Only `OWNER` is seeded; `V16__org_role_owner_only.sql` demoted the previously-shipped
`ADMIN` and `MEMBER` system roles to ordinary custom roles, and `RoleSeeder` no longer defines them.
The only request paths that read a role code are the last-owner guard, first-owner attachment at
provisioning, and the reserved-code check — none of which is an authorization decision.

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-ORG-1 | maintain a fixed catalog of exactly 15 permission codes and reject an unknown code on any role write with 422 and `source.pointer = /data/attributes/permissions` | `main:organization/Permission.java:13-30`; `main:organization/internal/RoleService.java:147` | `test:organization/internal/OrgRbacApiTest.unknownPermissionCodeOnRoleCreateIs422` |
| FR-ORG-2 | expose the permission catalog read-only at `GET /api/v1/permissions` to any authenticated caller, with no org scope | `main:organization/internal/PermissionCatalogController.java:24-30` | `test:organization/internal/OrgRbacApiTest.permissionCatalogIsReadableByAnyAuthenticatedUser` |
| FR-ORG-3 | seed exactly one system role per organization — `OWNER`, holding every permission in the catalog | `main:organization/internal/RoleSeeder.java:35-39` | `test:organization/internal/OrgRbacAuthorityTest.aFreshOrganizationHasExactlyOneRole`, `.ownerHasEveryPermissionIncludingOrgDelete` |
| FR-ORG-4 | treat a role named `ADMIN` or `MEMBER` as an ordinary custom role that an owner may create, rename, re-permission and delete | `V16__org_role_owner_only.sql`; `main:organization/internal/RoleService.java:25-30` | `test:organization/internal/OrgRbacApiTest.aRoleNamedAdminIsJustAnotherCustomRole`; `test:organization/internal/OrgRbacAuthorityTest.seederLeavesFormerSystemRolesAloneAsCustomRoles` |
| FR-ORG-5 | resolve a caller's permissions as the permission bundle of their **ACTIVE** membership's role in an **ACTIVE** organization, and return the empty set when the org is unknown or `SUSPENDED`, the membership is absent or `SUSPENDED`, or the role cannot be loaded. The `SUSPENDED` membership branch is enforced on the read path but has **no write path** — see the note below the table | `main:organization/internal/PermissionResolver.java:32-49` | `test:organization/internal/OrgRbacAuthorityTest.nonMemberHasNoPermissions`, `.suspendedOrganizationGrantsNothingUntilReactivated`, `.aSoftDeletedMembershipResolvesToZeroPermissions`, `.aMemberPointingAtASoftDeletedRoleResolvesToZeroPermissions`; the `SUSPENDED`-membership case has **no test** (nothing can produce one) |
| FR-ORG-6 | grant nothing on the basis of a role's **code** — a role grants exactly the permissions it was given | `main:organization/internal/PermissionResolver.java` (no code branch) | `test:organization/internal/OrgRbacAuthorityTest.aRoleCodeGrantsNothingByItself`, `.aCustomRoleGrantsExactlyWhatItWasGiven`, `.aReadOnlyRoleResolvesToExactlyItsReadPermissions` |
| FR-ORG-7 | deny any org-scoped check unless the target id **string-equals** the caller's single active organization id, with **no alias matching** and **no platform-role bypass**, before any database access | `main:shared/security/ApiPermissionEvaluator.java:36-58` | `test:organization/internal/OrgRbacApiTest.crossOrgAccessIsDeniedBeforeAnyDbHit`, `.tokenWithNoActiveOrgIsDenied`; `test:organization/internal/OrgRbacAuthorityTest.permissionsAreScopedToTheOwningOrganization` |
| FR-ORG-8 | cache resolved permission sets under `org-permissions` keyed `<orgId>:<subject>`, with the organization's status enforced **inside** the cached value | `main:organization/internal/PermissionResolver.java:19,32-41` | `test:organization/internal/OrgRbacAuthorityTest.permissionCacheIsEvictedAfterAMembershipRoleChange` |
| FR-ORG-9 | evict the permission cache on `RolePermissionsChanged`, `MembershipCreated`, `MembershipRoleChanged`, `MemberRemoved` and `OrganizationStatusChanged` | `main:organization/internal/OrgPermissionCacheEvictor.java:32-62` | `test:organization/internal/OrgRbacAuthorityTest.permissionCacheIsEvictedAfterAMembershipRoleChange`, `.suspendedOrganizationGrantsNothingUntilReactivated` |
| FR-ORG-10 | create an organization only for `platform-admin`, rejecting a duplicate alias — locally **or** in Keycloak — with 409 and never adopting an existing org, and rejecting an alias that is not a lowercase slug matching `^[a-z0-9][a-z0-9-]{0,118}[a-z0-9]$` (letters, digits, hyphens; minimum two characters; `@Size(max = 120)`) with 422 and `source.pointer = /data/attributes/alias` | `main:organization/internal/OrganizationController.java:41-44` (the `@Pattern`), `:53-58`; `main:organization/internal/OrganizationService.java:46-57` | `test:organization/internal/OrgRbacApiTest.duplicateAliasCreateIsConflictNotAdoption`, `.createRefusesToAdoptAnExistingKeycloakOrg`; the alias pattern itself has **no test** |
| FR-ORG-11 | on organization creation, create the Keycloak organization, provision the owner identity, add the owner as a Keycloak organization member, then atomically write the local org row, seed `OWNER` and create the first membership | `main:organization/internal/OrganizationService.java:79-84`; `main:organization/internal/OrgProjectionWriter.java:29-39` | **partial** — `test:organization/internal/OrgRbacApiTest.duplicateAliasCreateIsConflictNotAdoption`, `.createRefusesToAdoptAnExistingKeycloakOrg` reach `create` only as far as the conflict guard, and `test:organization/internal/KeycloakOrgAdminIntegrationTest.createAddFindRemovePinTheKeycloakOrganizationsWireContract` pins the Keycloak half against a real server. **No test calls `OrgProjectionWriter.projectWithOwner`** — `OrgRbacApiTest.seed` is a `@BeforeEach` fixture that assembles the projection by hand (`Organization.register` + `RoleSeeder.seedSystemRoles` + `Membership.create`), not through the production path. The orchestration end to end is unverified (§7.5) |
| FR-ORG-12 | address organizations on the wire by their **Keycloak organization id**, not the local primary key | `main:organization/internal/OrganizationController.java` (`ResourceObject.id = getKcOrgId()`) | `test:organization/internal/OrgRbacApiTest` (every org-scoped call) |
| FR-ORG-13 | permit reading an organization with `org:read` and renaming it with `org:update` | `main:organization/internal/OrganizationController.java:61-70` | `test:organization/internal/OrgRbacApiTest.memberCannotUpdateOrgButOwnerCan` |
| FR-ORG-14 | suspend and reactivate an organization only for `platform-admin`, and make suspension immediately revoke every member's org permissions | `main:organization/internal/OrganizationController.java:76-84`; `main:organization/internal/OrganizationService.java:105,116` | `test:organization/internal/OrgRbacApiTest.suspendIsPlatformAdminOnlyAndCutsMemberAccess` |
| FR-ORG-15 | list organization members (cursor-paginated) with `member:read`, invite with `member:invite`, reassign a role with `member:role:assign` and remove with `member:remove` | `main:organization/internal/MemberController.java:54-82` | `test:organization/internal/OrgRbacApiTest.memberCanReadButCannotInvite`, `.ownerInviteProvisionsAcrossModulesAndCreatesMembership`, `.aCustomRoleCarryingMemberInviteCanInvite`, `.aRemovedMemberLeavesTheListingAndReleasesTheirRole` |
| FR-ORG-16 | provision the invitee's identity, add them to the Keycloak organization and create the membership as one orchestrated invite, returning the existing membership unchanged when the person is already a member | `main:organization/internal/MemberService.java:65-105` | `test:organization/internal/OrgRbacApiTest.ownerInviteProvisionsAcrossModulesAndCreatesMembership` |
| FR-ORG-17 | reject an invite or role assignment naming a role code that does not exist in **this** organization, with 404 | `main:organization/internal/MemberService.java:166-169` (`requireRole`), `:172-175` (`lockRole`), `:181-183` (`notFound`) | `test:organization/internal/OrgRbacApiTest` (role resolution paths) |
| FR-ORG-18 | create, read, update and delete organization roles under `role:create` / `role:read` / `role:update` / `role:delete`, with roles listed **un-paginated**; a role code must match `^[A-Za-z][A-Za-z0-9_]{1,62}$` (start with a letter, then letters/digits/underscores only, minimum three characters, `@Size(max = 64)`) or the request is 422 with `source.pointer = /data/attributes/code`, and the permission set must be **non-empty** on both create and update (`@NotEmpty`) or the request is 422 with `source.pointer = /data/attributes/permissions` — a role with no permissions cannot be created or updated into existence | `main:organization/internal/RoleController.java:47-53` (`CreateRoleRequest`), `:55-56` (`UpdateRoleRequest`), `:59-90` | `test:organization/internal/OrgRbacApiTest.memberCannotCreateRoleButOwnerCan`, `.ownerMintsARoleAndItsHolderGetsExactlyThosePermissions`; the code pattern and the non-empty rule have **no test** |
| FR-ORG-19 | refuse to grant a permission the caller does not itself hold — on role create, role update, member invite and member role reassignment — with 403 naming the escalated codes | `main:organization/internal/PermissionEscalationGuard.java:41`; call sites `RoleService.java:77,93`, `MemberService.java:70,120` | `test:organization/internal/OrgRbacApiTest.aCallerCannotGrantAPermissionItDoesNotHold`, `.aNonOwnerCannotSelfPromoteToOwner`, `.aNonOwnerCannotInviteAnOwner` |
| FR-ORG-20 | reserve the code `OWNER`, rejecting an attempt to create it with 409 | `main:organization/internal/RoleService.java:30` | `test:organization/internal/OrgRbacApiTest.systemRoleUpdateIsForbidden` (adjacent); reserved-create itself has **no test** |
| FR-ORG-21 | reject an organization role code beginning with `PLATFORM` with 422 and `source.pointer = /data/attributes/code`, as a vocabulary guard and explicitly **not** a privilege boundary | `main:organization/internal/RoleService.java:37,68-72` | `test:organization/internal/OrgRbacApiTest.anOrgRoleCodeCannotBorrowThePlatformVocabulary` |
| FR-ORG-22 | forbid modifying or deleting a system role (403) | `main:organization/internal/Role.java:117-121`; `main:organization/internal/RoleService.java:114` | `test:organization/internal/OrgRbacApiTest.systemRoleUpdateIsForbidden` |
| FR-ORG-23 | refuse to delete a role that still has **live** members, with 409 | `main:organization/internal/RoleService.java:118-120` | `test:organization/internal/OrgRbacApiTest.aRemovedMemberLeavesTheListingAndReleasesTheirRole` |
| FR-ORG-24 | reject a duplicate role code within an organization with 409, both by pre-check and by the partial unique index `uq_org_role_org_code_live` | `main:organization/internal/RoleService.java:74,85`; `V17__soft_delete.sql:55` | `test:organization/internal/OrgRbacApiTest.aDeletedRoleCodeCanBeMintedAgain` |
| FR-ORG-25 | refuse to remove or demote the **last** owner of an organization, with 409, serialising concurrent attempts under a pessimistic write lock | `main:organization/internal/MemberService.java:154-164`; `main:organization/internal/MembershipRepository.java:22-25` | `test:organization/internal/OrgRbacApiTest.removingTheLastOwnerIsBlocked` |
| FR-ORG-26 | permit an owner to promote another member to owner | `main:organization/internal/MemberService.java:114-128` | `test:organization/internal/OrgRbacApiTest.ownerCanPromoteAMemberToOwner` |
| FR-ORG-27 | commit the local membership removal **before** the Keycloak unlink, and log rather than rethrow a failed unlink, since access is already revoked | `main:organization/internal/MemberService.java:130-152` | `test:organization/internal/OrgRbacApiTest.aRemovedMemberLeavesTheListingAndReleasesTheirRole` drives the real `remove` over HTTP (Keycloak gateway mocked), and `.removingTheLastOwnerIsBlocked` asserts `removeMember` is **never** called when the guard trips — i.e. the local half precedes the remote one. The swallow-and-log branch on a **failing** unlink has **no test**; no test makes the gateway throw |
| FR-ORG-28 | permit re-inviting a previously removed member into the same organization | `V17__soft_delete.sql:58-59` (`uq_membership_org_user_live`) | `test:organization/internal/OrgRbacAuthorityTest.aRemovedMemberCanBeInvitedBackIntoTheSameOrganization` |
| FR-ORG-29 | reconcile the seeded `OWNER` role's permissions back to the catalog at startup for every existing organization, leaving custom roles untouched | `main:organization/internal/SystemRoleCatalogReconciler.java`; `main:organization/internal/RoleSeeder.java:41-53` | `test:organization/internal/OrgRbacAuthorityTest.seederReconcilesADriftedSystemRoleBackToTheCatalog`, `.seederLeavesFormerSystemRolesAloneAsCustomRoles` |
| FR-ORG-30 | filter every org-scoped read by the tenant key **in the query**, not after loading | `main:organization/internal/RoleService.java:56-60,111`; `main:organization/internal/MemberService.java:109`; `main:webhooks/internal/WebhookSubscriptionService.java:52-54` | `test:organization/internal/OrgRbacAuthorityTest.permissionsAreScopedToTheOwningOrganization` |
| FR-ORG-31 | provide an opt-in, off-by-default **organization** dev bootstrap that seeds a first organization with an OWNER at startup so org RBAC is exercisable out of the box; idempotent, and best-effort — a Keycloak outage logs a warning rather than failing startup | `main:organization/internal/OrgDevBootstrap.java:16-42` (`@Component`, `@ConditionalOnProperty(name = "app.organization.dev-bootstrap.enabled", havingValue = "true")`, no `matchIfMissing`; `ApplicationRunner.run` → `OrganizationService.ensureBootstrap`); `main:organization/internal/OrgDevBootstrapProperties.java` | none |

**The organization dev bootstrap creates Keycloak state.** Unlike the identity bootstrap (FR-IDN-14),
which only projects an account that already exists, FR-ORG-31 goes through
`OrganizationService.ensureBootstrap` → `provision` (`main:organization/internal/OrganizationService.java:59-84`)
and, when the local projection is absent, will:

1. **find *or create* the Keycloak organization** — `keycloakOrg.findOrganizationIdByAlias(alias).orElseGet(() -> keycloakOrg.createOrganization(alias, name))`;
2. **provision the owner identity** through `UserProvisioning.provision`, which creates the Keycloak
   account if absent and triggers Keycloak's `execute-actions-email` invite (FR-IDN-5) — a real email
   to the configured address;
3. **add the owner as a Keycloak organization member** (`keycloakOrg.addMember`);
4. write the local `organization` row, seed the `OWNER` role and create the first `membership`
   (`OrgProjectionWriter.projectWithOwner`).

`provision` is the **only** path in the system that adopts a pre-existing Keycloak organization —
precisely the behaviour FR-ORG-10 forbids the admin-facing `create` from having. That asymmetry is
deliberate (it is what lets a dev environment heal after a local-DB reset), and it is the reason this
runner must stay off outside dev.

| Property | Default | Notes |
|---|---|---|
| `app.organization.dev-bootstrap.enabled` | `false` (`ORG_DEV_BOOTSTRAP_ENABLED`) | Gates the runner itself; absent means off |
| `app.organization.dev-bootstrap.alias` | `acme` (`ORG_DEV_BOOTSTRAP_ALIAS`) | Also defaulted in the record's compact constructor |
| `app.organization.dev-bootstrap.name` | `Acme` (`ORG_DEV_BOOTSTRAP_NAME`) | Also defaulted in the compact constructor |
| `app.organization.dev-bootstrap.owner-email` | `ayesigapo@gmail.com` (`ORG_DEV_BOOTSTRAP_OWNER_EMAIL`) | Also defaulted in the compact constructor; **this address receives a Keycloak invite** |
| `app.organization.dev-bootstrap.owner-first-name` | `Paul` (`ORG_DEV_BOOTSTRAP_OWNER_FIRST`) | `application.yaml:137` only — no code default; null is passed straight to Keycloak |
| `app.organization.dev-bootstrap.owner-last-name` | `Ayesiga` (`ORG_DEV_BOOTSTRAP_OWNER_LAST`) | `application.yaml:138` only — no code default |

Declared at `application.yaml:129-138` and explicitly disabled suite-wide by
`test/resources/application-test.yaml:30-33` ("No Keycloak in unit/IT context by default — the org
dev-bootstrap runner must not fire"), which is why it has no test.

**Membership status has no write path.** `MembershipStatus` declares `ACTIVE` and `SUSPENDED`, and
FR-ORG-5 fails closed on `SUSPENDED`. But the only assignment anywhere in `src/main` is
`Membership.create` setting `ACTIVE` (`main:organization/internal/Membership.java:45`); `Membership`
exposes no suspend or reinstate method, and every other reference reads the field
(`PermissionResolver.java:43`, `MemberService.java:160`, `MembershipRepository.java:25`). A
`SUSPENDED` membership is therefore reachable only by direct SQL — exactly like
`ProvisioningStatus.DISABLED` (§3.2). Suspension is done at the **organization** level (FR-ORG-14),
not the membership level.

**Catalog entries with no gate.** `org:delete`, `org:settings:read` and `org:settings:update` exist
in the `Permission` enum, are returned by `GET /api/v1/permissions`, and are granted to `OWNER` via
`EnumSet.allOf`, but **no `@PreAuthorize` anywhere references them** — there is no org-delete endpoint
and no org-settings endpoint. They are grantable and ungated.

### 3.4 Platform administration (FR-PLT)

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-PLT-1 | define exactly three platform realm roles — `platform-superadmin`, `platform-admin`, `platform-support` — as the only platform authority vocabulary | `main:shared/security/PlatformRole.java:13-33`; `docker/keycloak/realm-smsone.json:15-33` | `test:shared/security/PlatformRoleHierarchyTest` |
| FR-PLT-2 | make the tiers hierarchical so a check names the **minimum** tier that may pass it and every higher tier satisfies it | `main:shared/security/SecurityConfig.java:54-60` | `test:shared/security/PlatformRoleHierarchyTest.adminInheritsSupport`, `.superadminInheritsBothTiers` |
| FR-PLT-3 | apply the hierarchy to **method security**, re-supplying it to the custom expression handler that would otherwise opt out of the auto-configured one | `main:shared/security/SecurityConfig.java:67-78` | `test:shared/security/PlatformRoleHierarchyTest.supportReadsThePlatformViewButCannotChangePlatformBehaviour` |
| FR-PLT-4 | apply the same hierarchy to `CurrentUser.hasRole(...)`, so a hand-rolled check and `@PreAuthorize("hasRole(…)")` cannot answer differently | `main:shared/security/CurrentUserProvider.java:51-55`; `main:shared/security/CurrentUser.java:36-38` | `test:files/internal/FileApiTest.superadminInheritsTheDeleteTier`, `.supportMayReadAnyNamespace` |
| FR-PLT-5 | **not** run the hierarchy downwards: a lower tier must never satisfy a higher check | `main:shared/security/SecurityConfig.java:54-60` | `test:shared/security/PlatformRoleHierarchyTest.theLadderDoesNotRunDownwards`, `.anOrdinaryUserReachesNeitherTier` |
| FR-PLT-6 | map Keycloak `realm_access.roles` to `ROLE_<role>` and `resource_access.<client>.roles` to `ROLE_<client>_<role>`, so a **client** role can never satisfy a realm-role check | `main:shared/security/KeycloakJwtAuthenticationConverter.java:36-44` | `test:shared/security/KeycloakJwtAuthenticationConverterTest.mapsRealmRolesDirectlyAndNamespacesClientRoles`, `.clientRoleNamedPlatformAdminDoesNotBecomeRealmPlatformAdmin` |
| FR-PLT-7 | name the Spring principal from `preferred_username`, falling back to the subject when the claim is absent — while every durable key uses the subject | `main:shared/security/KeycloakJwtAuthenticationConverter.java:45-47` | `test:shared/security/KeycloakJwtAuthenticationConverterTest.fallsBackToSubjectWithoutPreferredUsername`; `test:shared/security/SubjectAttributionTest.theConverterDoesNameThePrincipalAfterTheUsername`, `.currentSubjectIsTheSubjectWhileUsernameStaysTheDisplayName` |
| FR-PLT-8 | accept only tokens whose audience includes the configured API audience, so a realm token minted for another client (notably the `smsone-admin` service account holding `realm-management` roles) cannot authenticate | `application.yaml:12-17`; realm client scope `smsone-api-audience` | **no negative test** — `KeycloakIntegrationTest` mints only via `smsone-web` and asserts acceptance |
| FR-PLT-9 | accept a real Keycloak-issued token end to end and let a real superadmin token reach a support-tier endpoint | `main:shared/security/SecurityConfig.java` | `test:shared/security/KeycloakIntegrationTest.securedEndpointAcceptsRealKeycloakJwt`, `.aRealSuperadminTokenReachesASupportTierEndpoint`, `.realmMintsTokensCarryingSubjectAndRealmRoles` |
| FR-PLT-10 | expose the platform audit trail at `GET /api/v1/audit` to `platform-support` | `main:audit/internal/AuditController.java:46-47` | `test:audit/internal/AuditApiTest.platformViewIsAdminOnly` |
| FR-PLT-11 | expose ShedLock rows read-only at `GET /api/v1/scheduler/locks` to `platform-support`, with **no trigger endpoint** — jobs are time-driven by design | `main:scheduler/internal/SchedulerController.java:19,33-34` | `test:scheduler/internal/SchedulerApiTest.adminListsShedLockRows`, `.nonAdminIsForbidden` |
| FR-PLT-12 | expose the analytics report catalogue and report runs to `platform-support` | `main:analytics/internal/AnalyticsReportController.java:37-47` | `test:analytics/internal/AnalyticsApiTest.nonAdminIsForbidden`, `.catalogListsTheAvailableReports` |
| FR-PLT-13 | restrict settings and feature-flag **writes** to `platform-admin` | `main:settings/internal/SettingController.java:45`; `main:settings/internal/FeatureFlagController.java:45` | `test:settings/SettingsApiIntegrationTest.nonAdminCannotUpsert`; `test:settings/FeatureFlagIntegrationTest.nonAdminCannotToggle` |
| FR-PLT-14 | keep the platform axis **disjoint** from the organization axis: a platform role grants no organization permission | `main:shared/security/ApiPermissionEvaluator.java:36-58` (no role branch) | `test:organization/internal/OrgRbacApiTest.crossOrgAccessIsDeniedBeforeAnyDbHit` |

### 3.5 Settings and feature flags (FR-SET)

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-SET-1 | expose settings as key/value/description resources, readable (list and single) by any authenticated, provisioned caller with **no permission or role check** | `main:settings/internal/SettingController.java:34-42` | `test:settings/SettingsApiIntegrationTest.adminUpsertsAndReadsSettingThroughTheEnvelope` |
| FR-SET-2 | upsert a setting on `PUT /api/v1/settings/{key}` under `platform-admin`, returning **200 on both create and update** (never 201) | `main:settings/internal/SettingController.java:44-45`; `main:settings/internal/SettingService.java:50-62` | `test:settings/SettingsApiIntegrationTest.adminUpsertsAndReadsSettingThroughTheEnvelope`, `.nonAdminCannotUpsert` |
| FR-SET-3 | return 404 for an unknown setting key | `main:settings/internal/SettingService.java` (`require`) | `test:settings/SettingsApiIntegrationTest.missingSettingYields404Envelope` |
| FR-SET-4 | reject a blank setting value with 422 | `main:settings/internal/SettingController.java` (`UpsertSettingRequest.value @NotBlank`) | `test:settings/SettingsApiIntegrationTest.blankValueYields422MultiErrorEnvelope` |
| FR-SET-5 | cache setting value lookups under `setting-values` and evict the key on every write **and** delete | `main:settings/internal/SettingService.java:17,43,49,71` | `test:settings/FeatureFlagIntegrationTest.evaluationIsCachedAndEvictedOnToggle` (the equivalent flag path) |
| FR-SET-6 | expose feature flags as key/enabled/description resources with the same read posture as settings | `main:settings/internal/FeatureFlagController.java:34-42` | `test:settings/FeatureFlagIntegrationTest.adminTogglesFlagThroughTheApi` |
| FR-SET-7 | evaluate an **unknown** feature flag as **off**, never as an error | `main:settings/internal/FeatureFlagService.java:30` | `test:settings/FeatureFlagIntegrationTest.unknownFlagIsOffNeverAnError` |
| FR-SET-8 | toggle a flag on `PUT /api/v1/feature-flags/{key}` under `platform-admin`, requiring an explicit boolean (a missing `enabled` is 422, never a silent `false`) | `main:settings/internal/FeatureFlagController.java:44-45` (`@NotNull Boolean enabled`) | `test:settings/FeatureFlagIntegrationTest.adminTogglesFlagThroughTheApi`, `.nonAdminCannotToggle` |
| FR-SET-9 | cache flag evaluation under `feature-flags` and evict on toggle and delete, so a kill switch reaches the fleet immediately rather than after the L2 TTL | `main:settings/internal/FeatureFlagService.java:17,30,48,70` | `test:settings/FeatureFlagIntegrationTest.evaluationIsCachedAndEvictedOnToggle` |
| FR-SET-10 | publish `SettingChanged` on every setting create/update and `FeatureFlagChanged` on every flag create/toggle | `main:settings/internal/Setting.java:40`; `main:settings/internal/FeatureFlag.java:41` | `test:settings/SettingsModuleTest.upsertPublishesSettingChangedThroughTheRegistry` |
| FR-SET-11 | audit setting and flag changes and deletions with before/after state | `main:settings/internal/SettingService.java:60,75`; `main:settings/internal/FeatureFlagService.java:59,74` | `test:audit/internal/AuditRecordingTest.settingChangeRecordsWhoWhatAndFromToState` |

**Gap.** `SettingService.delete(key)` and `FeatureFlagService.delete(key)` exist and are documented as
the only sanctioned removal path, but **neither controller declares a `DELETE` mapping**. There is no
HTTP route that deletes a setting or a feature flag. See §9.2.

### 3.6 Files (FR-FIL)

`FileController` carries **no `@PreAuthorize`**; authorization is a hand-rolled namespace check plus
a platform tier, and the tier differs by operation.

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-FIL-1 | store an uploaded object under a key minted as `u/<subject>/<uuid>/<sanitized-filename>`, sanitising the filename to `[A-Za-z0-9._-]` | `main:files/internal/FileController.java:131-136` | `test:files/internal/FileApiTest.uploadStoresUnderTheCallersNamespaceAndReturnsTheKey` |
| FR-FIL-2 | switch to S3 multipart upload above a 5 MiB threshold | `main:files/internal/FileController.java` (`MULTIPART_THRESHOLD_BYTES`); `main:files/internal/S3StorageProvider.java` | `test:files/FileStorageIntegrationTest.multipartUploadSurvivesRealSeaweedFs` (real SeaweedFS) |
| FR-FIL-3 | reject an empty upload with 422 and `source.parameter = "file"` | `main:files/internal/FileController.java:60-78` | `test:files/internal/FileApiTest.emptyUploadIs422` |
| FR-FIL-4 | serve downloads as a **302** redirect to a presigned URL valid for 10 minutes, never proxying the payload | `main:files/internal/FileController.java:80-88` | `test:files/internal/FileApiTest.downloadRedirectsToAPresignedUrlForTheOwner` |
| FR-FIL-5 | permit a caller to read only objects in their own `u/<subject>/` namespace, unless they hold `platform-support` or higher | `main:files/internal/FileController.java:83,148-153` | `test:files/internal/FileApiTest.aCallerCannotDownloadAnotherUsersFile`, `.supportMayReadAnyNamespace` |
| FR-FIL-6 | require `platform-admin` (a **higher** tier than read) to delete an object outside the caller's own namespace | `main:files/internal/FileController.java:91-97` | `test:files/internal/FileApiTest.supportMayNotDeleteAnotherUsersFile`, `.adminMayDeleteAnyNamespace`, `.superadminInheritsTheDeleteTier`, `.deleteRemovesAnOwnedObject` |
| FR-FIL-7 | mint a presigned **PUT** URL only under the caller's own subject, treating any supplied key purely as a filename hint, so no cross-namespace write path exists at any tier | `main:files/internal/FileController.java:99-130` | `test:files/internal/FileApiTest.presignPutMintsAnUploadUrlUnderTheCallersNamespace` |
| FR-FIL-8 | require a key for a presigned **GET**, apply the same owner-or-support check, and 404 when the object does not exist | `main:files/internal/FileController.java:99-130` | `test:files/FileStorageIntegrationTest.presignedGetAndPutWorkWithoutSdk` |
| FR-FIL-9 | reject any `operation` other than `GET` or `PUT` with 422 and `source.pointer = /data/attributes/operation` | `main:files/internal/FileController.java:99-130` | none |
| FR-FIL-10 | guard remote storage calls with a circuit breaker, while leaving presigning (local crypto) **outside** the breaker so it neither masks nor is blocked by a storage outage | `main:files/internal/S3StorageProvider.java:47-48`; `application.yaml` `resilience4j.circuitbreaker.instances.storage` | `test:files/ResilienceSmokeTest.circuitBreakerOpensUnderFaultInjection` |
| FR-FIL-11 | classify a missing object as the business outcome `FileNotFoundException`, configured as an ignored exception so it never trips the breaker | `main:files/internal/S3StorageProvider.java`; `application.yaml` (`ignore-exceptions`) | `test:files/ResilienceSmokeTest.circuitBreakerOpensUnderFaultInjection` |
| FR-FIL-12 | round-trip put/get/delete against a real S3-compatible store using path-style addressing and an explicit endpoint | `main:files/internal/S3ClientConfig.java`; `main:files/internal/S3StorageProvider.java` | `test:files/FileStorageIntegrationTest.putGetDeleteRoundtrip` |
| FR-FIL-13 | create the configured bucket at startup when absent and `app.storage.bootstrap-bucket` is true | `main:files/internal/BucketBootstrap.java` | none |

**Note.** No `spring.servlet.multipart.*` configuration exists in `application.yaml` or the test
profile, so upload size is bounded by Spring Boot's defaults rather than by an explicit project
choice — despite the code's own 5 MiB multipart threshold. File operations are **not audited**.

### 3.7 Notifications (FR-NOT)

Dispatch never sends inline: it enqueues into the durable `notification_delivery` queue and returns.

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-NOT-1 | expose a channel SPI (`NotificationChannelSender`) as the documented extension point, registering exactly one sender per channel and ignoring a duplicate with a warning | `main:notification/NotificationChannelSender.java`; `main:notification/internal/ChannelRegistry.java:20-31` | none (registry collision is not tested) |
| FR-NOT-2 | support the channels `EMAIL`, `SMS`, `IN_APP`, `SLACK`, `WEBHOOK` | `main:notification/NotificationChannel.java` | `test:notification/NotificationDeliveryTest.dispatchFansOutAcrossChannels` |
| FR-NOT-3 | durably enqueue one delivery row per recipient on `dispatch(...)` and return immediately without sending | `main:notification/internal/NotificationService.java:32-42`; `main:notification/internal/NotificationDeliveryQueue.java:38-60` | `test:notification/NotificationDeliveryTest.dispatchFansOutAcrossChannels` |
| FR-NOT-4 | notify configured admins by email, plus in-app when the admin's subject can be resolved from their email, so an unprovisioned admin still receives the email | `main:notification/internal/NotificationService.java:44-59` | `test:notification/NotificationDeliveryTest.flagToggleEnqueuesThenWorkerDeliversEmailAndInApp` |
| FR-NOT-5 | claim delivery rows atomically with `FOR UPDATE SKIP LOCKED`, taking due `PENDING` rows and reclaiming `PROCESSING` rows whose lock is older than `stale-lock` | `main:notification/internal/NotificationDeliveryQueue.java:68-108` | `test:notification/internal/NotificationDeliveryQueueTest.staleClaimantsUpdatesAreFencedOutAfterReclaim` |
| FR-NOT-6 | **fence** every terminal update on `status = 'PROCESSING' and attempts = <claimant's count>`, so a stale claimant whose row was re-claimed updates zero rows and only logs | `main:notification/internal/NotificationDeliveryQueue.java:110-140` | `test:notification/internal/NotificationDeliveryQueueTest.staleClaimantsUpdatesAreFencedOutAfterReclaim` |
| FR-NOT-7 | dead-letter a row whose stored channel no longer maps to the enum, in place, rather than let it poison every batch it lands in | `main:notification/internal/NotificationDeliveryQueue.java:99-106` | `test:notification/internal/NotificationDeliveryQueueTest.unknownChannelRowIsDeadLetteredInsteadOfPoisoningTheBatch` |
| FR-NOT-8 | dead-letter immediately on a **permanent** failure (a receiver status `< 500` other than 408 and 429) without burning retries | `main:notification/internal/NotificationDeliveryWorker.java:177-230`; `main:notification/internal/HttpChannels.java` | `test:notification/NotificationDeliveryTest.permanent4xxIsDeadLetteredWithoutBurningRetries` |
| FR-NOT-9 | retry a transient failure with capped exponential backoff up to `max-attempts`, then dead-letter | `main:notification/internal/NotificationDeliveryWorker.java:210-219` (the dead-letter / reschedule branch), `:232-237` (`backoff`) | `test:notification/NotificationDeliveryTest.transient5xxIsRetriedWithBackoffUntilDeadLettered` |
| FR-NOT-10 | dead-letter a delivery whose channel has no registered sender rather than silently dropping it | `main:notification/internal/NotificationDeliveryWorker.java:177-230` | none |
| FR-NOT-11 | defer a delivery whose channel egress rate is exhausted **without** counting it as a failed attempt (`attempts = greatest(attempts - 1, 0)`), recording the start of the continuous throttled stretch | `main:notification/internal/NotificationDeliveryQueue.java:124-132`; `V12__notification_delivery_throttle.sql` | `test:notification/internal/NotificationDeliveryQueueTest.throttledSinceTracksTheContinuousThrottleStretchAndClearsOnRealAttempt`; `test:shared/ratelimit/RateLimitIntegrationTest.egressChannelLimitDefersExcessDeliveries` |
| FR-NOT-12 | dead-letter a delivery throttled continuously beyond `throttle-max-age`, as a mis-set-rate guard | `main:notification/internal/NotificationDeliveryWorker.java:177-230` | none |
| FR-NOT-13 | refund the egress token when a send throws, since the send may never have reached the provider | `main:notification/internal/NotificationDeliveryWorker.java:177-230`; `main:notification/internal/ChannelRateLimiter.java` | none |
| FR-NOT-14 | on a send that succeeded but whose status write failed, log and leave the row `PROCESSING` for stale reclaim — **never** reschedule or dead-letter, which would re-send an already-sent message | `main:notification/internal/NotificationDeliveryWorker.java:224-229` (the `markSent` try/catch; `:222-223` is the comment stating the rule) | none |
| FR-NOT-15 | not duplicate a message under concurrent fan-out | `main:notification/internal/NotificationDeliveryWorker.java:143-175` | `test:notification/NotificationDeliveryTest.fansOutHundredsConcurrentlyWithoutDuplicates` |
| FR-NOT-16 | bound every outbound HTTP exchange (Slack, webhook channel) by a whole-exchange timeout and abort the connection on expiry | `main:notification/internal/HttpChannels.java:24-60` | none |
| FR-NOT-17 | SSRF-guard every caller-supplied outbound URL on the Slack and webhook channels, treating an unsafe address as a permanent failure unless the guard says it is retryable — **unless `app.notification.webhook-allow-private-hosts` is true**, which skips address resolution entirely (see the note below the table) | `main:notification/internal/WebhookChannelSender.java:13,41`; `main:notification/internal/SlackChannelSender.java:36-40`; `main:notification/internal/NotificationProperties.java:20,31-33`; `main:shared/http/SafeOutboundUrl.java` | `test:shared/http/SafeOutboundUrlTest` (all six tests, including `.allowPrivateHostsBypassesTheAddressCheck`) |
| FR-NOT-18 | address in-app notifications by the recipient's **immutable subject**, never a username or email | `main:notification/Recipient.java:24`; `main:notification/internal/InAppChannelSender.java:25` | `test:notification/NotificationDeliveryTest.flagToggleEnqueuesThenWorkerDeliversEmailAndInApp` |
| FR-NOT-19 | list only the caller's own in-app notifications (cursor-paginated), scoped by subject, with no `@PreAuthorize` | `main:notification/internal/InAppNotificationService.java:37-61`; `main:notification/internal/NotificationController.java:30` | none (no dedicated API test) |
| FR-NOT-20 | return **404** when marking a notification read that does not exist **or** belongs to another user — never 403, so existence is not disclosed | `main:notification/internal/InAppNotificationService.java` | none |
| FR-NOT-21 | mark-read via a conditional bulk update that does not bump `@Version`, so concurrent mark-reads cannot produce an optimistic-lock 500 | `main:notification/internal/InAppNotificationRepository.java` (`markReadIfUnread`) | none |
| FR-NOT-22 | purge terminal (`SENT` and `FAILED`) delivery rows older than `app.notification.delivery.retention` (default P7D) via a nightly ShedLock-guarded, batched job | `main:notification/internal/NotificationRetentionJob.java` | `test:notification/internal/NotificationRetentionJobTest.purgesOldTerminalRowsAndNothingElse` |
| FR-NOT-23 | stop the worker before the DataSource closes and wait up to 20 s for in-flight sends to record their status | `main:notification/internal/NotificationDeliveryWorker.java` (`SmartLifecycle`, phase `MAX_VALUE - 100`) | none |

**The SSRF guard has an opt-out.** `app.notification.webhook-allow-private-hosts` (a `Boolean` on
`NotificationProperties`, defaulted to **`FALSE`** in the compact constructor when absent; it has **no
entry in `application.yaml`**) is passed to `SafeOutboundUrl.requireSafe` by both
`WebhookChannelSender` and `SlackChannelSender`. When true, `requireSafe` returns immediately after
the scheme/host check and **never resolves the host**
(`main:shared/http/SafeOutboundUrl.java:46-48`) — loopback, RFC-1918, link-local and cloud-metadata
targets are all accepted. The test profile sets it to `true`
(`test/resources/application-test.yaml:42`) because test receivers run on localhost.

**Note.** `SmsChannelSender` is a **logging stub with no gateway**, registered by default
(`@ConditionalOnProperty(name = "app.notification.sms.stub", havingValue = "true", matchIfMissing = true)`).
Any deployment believing it sends SMS is wrong. The only production caller of `Notifications` is
`FeatureFlagChangeNotifier`, so `FeatureFlagChanged` is currently the sole event that produces a
notification.

### 3.8 Webhooks (FR-WHK)

Per-organization outbound subscriptions with HMAC-signed, durably-queued delivery. Every endpoint
requires the single permission `webhook:manage`.

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-WHK-1 | let an organization subscribe an HTTPS endpoint to a subset of a fixed event vocabulary — `org.member.added`, `org.member.removed`, `org.member.role_changed`, `org.role.permissions_changed`, `org.status_changed` | `main:webhooks/internal/WebhookEventType.java:12-18`; `main:webhooks/internal/WebhookController.java:57` | `test:webhooks/internal/WebhookApiTest.createReturnsTheSecretOnceThenReadsMaskIt` |
| FR-WHK-2 | reject an unknown event code with 422 and `source.pointer = /data/attributes/events`, and reject an empty event set | `main:webhooks/internal/WebhookSubscriptionService.java:120` | `test:webhooks/internal/WebhookApiTest.unknownEventTypeIs422` |
| FR-WHK-3 | SSRF-guard the subscription URL at create/update (422) **and** again at send time (permanent failure) — **unless `app.webhooks.allow-private-hosts` is true**, which skips address resolution at both points (see the note below the table) | `main:webhooks/internal/WebhookSubscriptionService.java:100-106`; `main:webhooks/internal/WebhookSender.java:74-80`; `main:webhooks/internal/WebhookProperties.java:17` | `test:webhooks/internal/WebhookApiTest.nonHttpUrlIsRejected`; `test:shared/http/SafeOutboundUrlTest` |
| FR-WHK-4 | generate a signing secret from a `SecureRandom`, return it **in full only on create**, and mask it on every subsequent read | `main:webhooks/internal/WebhookSubscriptionService.java:44,126-130`; `main:webhooks/internal/WebhookController.java:62,115-119` | `test:webhooks/internal/WebhookApiTest.createReturnsTheSecretOnceThenReadsMaskIt` |
| FR-WHK-5 | fan an organization event out to every **ACTIVE** subscription in that organization that subscribes to the event's code, deduplicating on `(listener, messageId)` through the inbox | `main:webhooks/internal/WebhookDispatcher.java:31-44`; `main:webhooks/internal/WebhookEventListener.java:25-74` | `test:webhooks/internal/WebhookEventTest.aMemberAddedEventEnqueuesADeliveryForASubscriber` |
| FR-WHK-6 | build the signed payload by explicit byte construction, so the exact bytes signed are the exact bytes sent regardless of mapper configuration | `main:webhooks/internal/WebhookPayload.java:9-11,37-74` | `test:webhooks/internal/WebhookDeliveryTest.deliversASignedPayloadAndMarksDelivered` |
| FR-WHK-7 | sign each delivery `HmacSHA256(secret, payload)` and send `X-Webhook-Signature: sha256=<hex>` alongside `X-Webhook-Event` and `X-Webhook-Delivery` | `main:webhooks/internal/WebhookSigner.java:17-25`; `main:webhooks/internal/WebhookSender.java:39-46` | `test:webhooks/internal/WebhookDeliveryTest.deliversASignedPayloadAndMarksDelivered` |
| FR-WHK-8 | join the signing secret at **claim** time so it is never copied into the delivery row | `main:webhooks/internal/WebhookDeliveryQueue.java:16-23,68-92` | `test:webhooks/internal/WebhookDeliveryTest.deliversASignedPayloadAndMarksDelivered` |
| FR-WHK-9 | retry a transient failure (5xx, 408, 429, timeout, DNS) with capped exponential backoff up to `max-attempts` (default 5), then dead-letter, recording the last response status | `main:webhooks/internal/WebhookDeliveryWorker.java:132-157`; `main:webhooks/internal/WebhookSender.java:66-70` | `test:webhooks/internal/WebhookDeliveryTest.transientFailureIsRetriedThenDeadLettered` |
| FR-WHK-10 | fence every terminal delivery update on `status = 'PROCESSING' and attempts = ?` | `main:webhooks/internal/WebhookDeliveryQueue.java:94-136` | `test:webhooks/internal/WebhookDeliveryTest.transientFailureIsRetriedThenDeadLettered` |
| FR-WHK-11 | **never claim** a delivery whose subscription has been soft-deleted, spelling `s.deleted_at is null` into the native claim SQL because `@SQLRestriction` cannot reach it | `main:webhooks/internal/WebhookDeliveryQueue.java:61-81` | `test:webhooks/internal/WebhookDeliveryTest.aPendingDeliveryForADeletedSubscriptionIsNeverClaimed` |
| FR-WHK-12 | cancel outstanding queued deliveries in the same transaction as a subscription delete, so the log says what happened rather than leaving rows indefinitely `PENDING` | `main:webhooks/internal/WebhookSubscriptionService.java:66-84`; `main:webhooks/internal/WebhookDeliveryQueue.java:121-125` | `test:webhooks/internal/WebhookDeliveryTest.deletingASubscriptionStopsDeliveriesAlreadyQueued` |
| FR-WHK-13 | keep the delivery log readable after the subscription is deleted, while still enforcing tenant scope, resolving the subscription through a native `existsIncludingDeleted` rather than handing out a deleted aggregate | `main:webhooks/internal/WebhookSubscriptionService.java:87-98`; `main:webhooks/internal/WebhookSubscriptionRepository.java:20-28` | `test:webhooks/internal/WebhookApiTest.theDeliveryLogOutlivesTheSubscriptionButStaysTenantScoped` |
| FR-WHK-14 | expose the delivery log cursor-paginated per subscription, exposing outcome only — never the request payload | `main:webhooks/internal/WebhookController.java:95-96`; `main:webhooks/internal/WebhookDelivery.java:10-15` (payload deliberately unmapped) | `test:webhooks/internal/WebhookApiTest.theDeliveryLogOutlivesTheSubscriptionButStaysTenantScoped` |
| FR-WHK-15 | deny every webhook endpoint to a caller without `webhook:manage` | `main:webhooks/internal/WebhookController.java:58,66,73,80,89,96` | `test:webhooks/internal/WebhookApiTest.withoutThePermissionAccessIsDenied` |
| FR-WHK-16 | soft-delete a subscription on `DELETE`, returning 204 | `main:webhooks/internal/WebhookSubscriptionService.java:80-84` | `test:webhooks/internal/WebhookApiTest.deleteRemovesTheSubscription` |
| FR-WHK-17 | carry a subscription lifecycle **status** of `ACTIVE` or `DISABLED` (`main:webhooks/internal/SubscriptionStatus.java`), created as `ACTIVE`, returned as an attribute on **every** read (create, list, get, update), skipped by fan-out while `DISABLED` (`subscribesTo` returns false unless `ACTIVE`), and settable only through `PUT` — where an **omitted, null or blank** `status` means `ACTIVE`, so a `PUT` that does not mention status silently **re-enables** a disabled subscription, and any other value is 422 with `source.pointer = /data/attributes/status` | `main:webhooks/internal/WebhookSubscription.java:36-38,50,54-58,60-62`; `main:webhooks/internal/WebhookController.java:44,54,79-86,103-113` (`parseStatus`); `V15__webhooks.sql:9` | none — no test creates, reads or asserts a `DISABLED` subscription |

**The SSRF guard has an opt-out.** `app.webhooks.allow-private-hosts`
(`application.yaml:174`, default **`false`** via `WEBHOOKS_ALLOW_PRIVATE_HOSTS`) is passed to
`SafeOutboundUrl.requireSafe` at both the configure-time check (`WebhookSubscriptionService:102`) and
the send-time check (`WebhookSender:76`). When true, `requireSafe` returns immediately after the
scheme/host check and **never resolves the host** (`main:shared/http/SafeOutboundUrl.java:46-48`), so
a tenant may subscribe — and the worker will POST to — loopback, RFC-1918, link-local and
cloud-metadata addresses. It is a single flag that disables the guard for the whole module; the test
profile sets it to `true` (`test/resources/application-test.yaml:37`).

**Gaps.** Webhook subscription mutations — including secret creation and rotation — write **no
`audit_log` row**: `WebhookSubscriptionService` has no `AuditLog` dependency. The signing secret is
stored as plaintext `varchar(200)`. `WebhookDeliveryQueue.purgeDeliveredBefore` exists but has **no
caller anywhere** and there is no `app.webhooks.retention` property, so `webhook_delivery` grows
without bound despite `V17`'s header and the service javadoc describing it as retention-trimmed. The
subscribable event vocabulary is published nowhere on the wire — no catalog endpoint, and the
OpenAPI spec types `events` as a bare string array with no enum.

### 3.9 Audit (FR-AUD)

**Auditing is synchronous through a shared port, not event-driven.** `audit/internal` contains no
`@ApplicationModuleListener` at all; `audit/package-info.java` still claims otherwise and is stale.

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-AUD-1 | offer every module a dependency-free way to record an audit row — `AuditLog.record(action, orgId, target, fromState, toState)` — called at the point of change and inside the changing transaction | `main:shared/audit/AuditLog.java`; `main:audit/internal/AuditLogImpl.java:30-34` | `test:audit/internal/AuditRecordingTest.settingChangeRecordsWhoWhatAndFromToState` |
| FR-AUD-2 | record the acting principal as the token **subject**, and `null` for a system-triggered change | `main:audit/internal/AuditLogImpl.java:32` | `test:audit/internal/AuditRecordingTest.settingChangeRecordsWhoWhatAndFromToState`, `.systemTriggeredChangeHasNoActor` |
| FR-AUD-3 | record who, when (`occurred_at` from the injected `Clock`, plus `created_at` as the audit timeline), where (`org_id`, null for platform-level), what (`action`, `target`) and the before/after state | `main:audit/internal/AuditEntry.java`; `V13__audit_log.sql`, `V14__audit_log_state.sql` | `test:audit/internal/AuditRecordingTest.settingChangeRecordsWhoWhatAndFromToState` |
| FR-AUD-4 | truncate every audit string field to its column length at construction, so an over-long value degrades rather than throwing | `main:audit/internal/AuditEntry.java:60-65` | none |
| FR-AUD-5 | keep `audit_log` **append-only**: never soft-deletable, never mutated, and surviving the deletion of whatever it describes | `main:audit/internal/AuditEntry.java` (extends `BaseEntity`); `V17__soft_delete.sql:4` | `test:audit/internal/AuditRecordingTest.theTrailSurvivesTheSoftDeleteOfWhatItDescribes` |
| FR-AUD-6 | expose a platform-wide audit view at `GET /api/v1/audit` requiring `platform-support`, filterable by `org` (exact `org_id`), `action` (exact match, blank ignored), `from` and `to`. **The window bounds `occurred_at`, not `created_at`**: `occurred_at >= from` (inclusive) and `occurred_at < to` (exclusive), while the sort, the keyset cursor and `links.next` all key on `created_at desc, id desc`. The two are different columns on the same row — `occurred_at` is when the change happened, `created_at` is when it was recorded (`V13__audit_log.sql:12,14`) — so a time-filtered page walk is ordered by a timestamp the filter does not constrain | `main:audit/internal/AuditController.java:46-47,77-94` (`cb.greaterThanOrEqualTo(root.get("occurredAt"), from)`, `cb.lessThan(root.get("occurredAt"), to)`), `:34` (`NEWEST_FIRST`), `:73` | `test:audit/internal/AuditApiTest.platformAdminListsAndFiltersByAction`, `.platformViewIsAdminOnly`; the `occurred_at`-vs-`created_at` split has **no test** |
| FR-AUD-7 | expose an org-scoped audit view at `GET /api/v1/orgs/{orgId}/audit` requiring the `audit:read` permission, with the org filter forced to the path | `main:audit/internal/AuditController.java:57-58` | `test:audit/internal/AuditApiTest.orgScopedViewReturnsOnlyThatOrgWhenPermitted`, `.orgScopedViewDeniesWithoutTheAuditPermission` |
| FR-AUD-8 | return both views newest-first and cursor-paginated | `main:audit/internal/AuditController.java:34` (`createdAt desc, id desc`); `V13__audit_log.sql:21-23` | `test:audit/internal/AuditApiTest.platformViewIsCursorPaginated` |
| FR-AUD-9 | reject a non-ISO-8601 `from`/`to` with 422 and `source.parameter` naming the offending parameter | `main:audit/internal/AuditController.java:96-106` | `test:audit/internal/AuditApiTest.badInstantFilterIs422` |
| FR-AUD-10 | audit these 22 actions: `identity.user_provisioned`, `.user_disabled_by_reconciliation`; `organization.created`, `.renamed`, `.suspended`, `.reactivated`, `.member_added`, `.member_role_changed`, `.member_removed`, `.role_created`, `.role_updated`, `.role_deleted`; `platform.impersonation_started`, `._ended`, `._superseded`; `settings.changed`, `.deleted`, `.feature_flag_changed`, `.feature_flag_deleted`; `webhooks.subscription_created`, `.subscription_updated`, `.subscription_deleted` | see §8 traceability | `test:audit/internal/AuditApiTest.platformAdminListsAndFiltersByAction` |

**Known deviation.** `AuditController.query` calls the **unvalidated** `page.scrollPosition()`
(`main:audit/internal/AuditController.java:73`) while every other paginated site uses
`scrollPosition(SORT)`. Per `CursorPageRequest`'s own javadoc and `AGENTS.md` §3.3, a cursor minted
for a different collection therefore surfaces here as a 500 rather than a 422.

**Not audited:** all webhook subscription mutations, all file operations, notification mark-read.

### 3.10 Analytics (FR-ANA)

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-ANA-1 | offer a fixed report catalogue selected by **code**, never accepting SQL from a client | `main:analytics/internal/AnalyticsReport.java`; `main:analytics/internal/AnalyticsReportController.java` | `test:analytics/internal/AnalyticsApiTest.catalogListsTheAvailableReports` |
| FR-ANA-2 | provide the reports `users-by-status` and `delivery-outcomes`, restricted to `platform-support` | `main:analytics/internal/AnalyticsReport.java`; `main:analytics/internal/AnalyticsReportController.java:37-47` | `test:analytics/internal/AnalyticsApiTest.runningAReportMaterializesAndAggregates`, `.nonAdminIsForbidden` |
| FR-ANA-3 | return 404 for an unknown report code | `main:analytics/internal/AnalyticsReportController.java` | `test:analytics/internal/AnalyticsApiTest.unknownReportIs404` |
| FR-ANA-4 | materialize marts from Postgres by cursor-streamed extraction into a staging table and swap them in atomically, leaving the previous mart intact when a refresh fails | `main:analytics/internal/DuckDbAnalyticsEngine.java:116-144` | `test:analytics/AnalyticsIntegrationTest.failedRefreshLeavesThePreviousMartIntact`, `.kpiOverPostgresDataViaDuckDb` |
| FR-ANA-5 | exclude soft-deleted rows in report source SQL explicitly, because raw JDBC bypasses `@SQLRestriction` | `main:analytics/internal/AnalyticsReport.java:12-22` | `test:analytics/internal/AnalyticsApiTest.softDeletedUsersAreExcludedFromTheReport` |
| FR-ANA-6 | preserve decimal exactness across the Postgres→DuckDB boundary, mapping `NUMERIC/DECIMAL` to `DECIMAL(p,s)` and never to `DOUBLE` | `main:analytics/internal/DuckDbAnalyticsEngine.java` | `test:analytics/AnalyticsIntegrationTest.decimalsStayExactMoneyNeverDrifts` |
| FR-ANA-7 | pin every DuckDB connection to UTC and bind `timestamptz` as `OffsetDateTime`, so day buckets are host-independent | `main:analytics/internal/DuckDbAnalyticsEngine.java:217` (`SET TimeZone = 'UTC'`, inside `open` at `:201-223`), `:282-288` (`isTimestampTz` — pgjdbc reports `timestamptz` as plain `Types.TIMESTAMP`, so the type **name** is the signal), `:296-300` (the `OffsetDateTime` bind in `bindColumn`) | `test:analytics/AnalyticsIntegrationTest.dayBucketsAreUtcDeterministicRegardlessOfHostTimezone` |
| FR-ANA-8 | run ephemeral queries on a throwaway in-memory database, bounded by a permit semaphore | `main:analytics/internal/DuckDbAnalyticsEngine.java:60` | `test:analytics/AnalyticsIntegrationTest.ephemeralQueriesRunOnAThrowawayDatabase` |
| FR-ANA-9 | export Parquet snapshots only to filenames matching `^[A-Za-z0-9][A-Za-z0-9._-]{0,200}$` and only to paths that resolve inside the configured snapshot directory | `main:analytics/internal/DuckDbAnalyticsEngine.java:146-167` | `test:analytics/AnalyticsIntegrationTest.snapshotFileNamesCannotEscapeTheSnapshotDirectory`, `.parquetSnapshotRoundtrip` |
| FR-ANA-10 | keep Postgres the system of record, with analytics behind the `AnalyticsEngine` seam so the engine is a pure implementation swap | `main:analytics/AnalyticsEngine.java`; `main:analytics/package-info.java`; ADR 0006 | `test:ModularityTests.verifiesModularStructure` |

### 3.11 Scheduling (FR-SCH)

Exactly three `@Scheduled` methods exist in `src/main`.

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-SCH-1 | run scheduled jobs at most once across the cluster, using a DB-server-time lock so instances need no clock agreement | `main:scheduler/internal/SchedulingConfig.java` (`JdbcTemplateLockProvider … usingDbTime()`); `V4__shedlock.sql` | `test:scheduler/SchedulerLockIntegrationTest.onlyOneOfTwoInstancesAcquiresTheLock`, `.lockRowIsWrittenToTheShedlockTable` |
| FR-SCH-2 | purge completed event publications older than `app.scheduler.event-retention` (default P7D) nightly | `main:scheduler/internal/EventPublicationPurgeJob.java:30` (`@Scheduled(cron = "${app.scheduler.event-purge-cron:0 0 3 * * *}")`, lock `event-publication-purge`) | `test:scheduler/EventPurgeJobIntegrationTest.purgesCompletedPublications` |
| FR-SCH-3 | purge idempotency keys older than `app.idempotency.retention` (default P1D) nightly | `main:scheduler/internal/IdempotencyPurgeJob.java:27` (`@Scheduled(cron = "${app.scheduler.idempotency-purge-cron:0 30 3 * * *}")`, lock `idempotency-key-purge`) | none |
| FR-SCH-4 | purge soft-deleted rows past retention nightly — see FR-DLC-6..8 | `main:scheduler/internal/SoftDeletePurgeJob.java:98` (`@Scheduled(cron = "${app.scheduler.soft-delete-purge-cron:0 0 4 * * *}")`, lock `soft-delete-purge`) | `test:scheduler/internal/SoftDeletePurgeJobIntegrationTest` (five tests) |
| FR-SCH-5 | expose lock state read-only and provide **no** endpoint that triggers a job | `main:scheduler/internal/SchedulerController.java` | `test:scheduler/internal/SchedulerApiTest.adminListsShedLockRows` |

**The three times above are defaults, not constants.** Each `@Scheduled` cron is a property
placeholder — `app.scheduler.event-purge-cron`, `app.scheduler.idempotency-purge-cron`,
`app.scheduler.soft-delete-purge-cron` — and none of the three appears in `application.yaml`, so the
literal in the annotation is the shipped default and an operator may override any of them without a
code change. See §10.

**Not scheduled.** The two durable-queue workers (`NotificationDeliveryWorker`,
`WebhookDeliveryWorker`) are `SmartLifecycle` pollers on virtual threads, **not** `@Scheduled` and
**not** ShedLock-guarded; they rely on `FOR UPDATE SKIP LOCKED` for cluster safety instead.

### 3.12 Domain events (FR-EVT)

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-EVT-1 | publish domain events registered on an aggregate when the repository saves it | `main:shared/persistence/AggregateRoot.java:21-33` | `test:settings/SettingsModuleTest.upsertPublishesSettingChangedThroughTheRegistry` |
| FR-EVT-2 | persist one publication row per *(event, registered listener)* in `event_publication`, and republish incomplete publications on restart — at-least-once delivery | `V2__modulith_event_publication.sql`; `application.yaml:53-55` | `test:scheduler/EventPurgeJobIntegrationTest.purgesCompletedPublications` |
| FR-EVT-3 | publish these ten events: `SettingChanged`, `FeatureFlagChanged`, `UserProvisioned`, `UserActivated`, `OrganizationRegistered`, `OrganizationStatusChanged`, `MembershipCreated`, `MembershipRoleChanged`, `MemberRemoved`, `RolePermissionsChanged` | module API packages | `docs/EVENTS.md`; `test:settings/SettingsModuleTest`, `test:webhooks/internal/WebhookEventTest` |
| FR-EVT-4 | publish `MemberRemoved` **explicitly**, because a repository delete fires no `@DomainEvents` | `main:organization/internal/MemberService.java:138-140` | `test:organization/internal/OrgRbacAuthorityTest.aSoftDeletedMembershipResolvesToZeroPermissions`, `.aRemovedMemberCanBeInvitedBackIntoTheSameOrganization` — both call the private helper `removeMemberAsProductionDoes` (`:276`), which reproduces the delete-then-publish pair and then awaits the cache eviction that only the explicit publish can cause |
| FR-EVT-5 | register `RolePermissionsChanged` on role **soft-delete** as well as on custom-role edit and system-role reconciliation, since deletion must evict cached permissions and notify subscribers | `main:organization/internal/Role.java:78,92,108` | `test:organization/internal/RoleSoftDeleteTest.deletingARoleHidesItWithoutTouchingItsPermissions` |
| FR-EVT-6 | provide consumer-side deduplication via `EventInbox.recordIfNew(listenerId, messageId)`, exactly once per pair, joining the caller's transaction so a rolled-back listener leaves no inbox record | `main:shared/events/EventInbox.java:28-34`; `V7__event_inbox.sql` | `test:shared/events/EventInboxIntegrationTest.exactlyOncePerListenerAndMessage`, `.recordJoinsTheCallersTransaction` |
| FR-EVT-7 | guard the two event consumers that have side effects with the inbox (`notification-flag-change`, `webhooks`), while the cache evictor deliberately uses none because a cache clear is idempotent | `main:notification/internal/FeatureFlagChangeNotifier.java:18,33`; `main:webhooks/internal/WebhookDispatcher.java:31-44`; `main:organization/internal/OrgPermissionCacheEvictor.java` | `test:webhooks/internal/WebhookEventTest.aMemberAddedEventEnqueuesADeliveryForASubscriber` |

**Facts `docs/EVENTS.md` omits.** (1) Role soft-delete is a third publisher of
`RolePermissionsChanged`. (2) Four events — `SettingChanged`, `UserProvisioned`, `UserActivated`,
`OrganizationRegistered` — have **zero production consumers**, and because the registry stores a row
per *(event, listener)* they therefore produce **no `event_publication` rows at all**;
`EventPurgeJobIntegrationTest` has to import its own probe listener to make the purge observable.
(3) The webhook wire-code mapping is documented nowhere. Additionally, `event_inbox` has **no purge
job** and grows unbounded, and `SettingChanged` is the only event without `occurredAt`.

### 3.13 Data lifecycle — soft delete and purge (FR-DLC)

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-DLC-1 | record deletion rather than executing it for all seven aggregate tables — `setting`, `feature_flag`, `app_user`, `organization`, `org_role`, `membership`, `webhook_subscription` | `V17__soft_delete.sql:23-29`; each entity's `@SQLDelete` | `test:shared/persistence/SoftDeleteTest.deleteStampsTheRowInsteadOfRemovingIt` |
| FR-DLC-2 | hide a soft-deleted row from **every** JPA read path via `@SQLRestriction("deleted_at is null")` declared on each entity (Hibernate inherits neither annotation from a mapped superclass) | seven entities; `main:shared/persistence/SoftDeletableEntity.java:12-14` | `test:shared/persistence/SoftDeleteTest.everyJpaReadPathStopsSeeingADeletedRow`, `.markDeletedHidesTheRowThroughTheSameRestriction` |
| FR-DLC-3 | bump `version = version + 1` inside the soft-delete statement and predicate it on `id = ? and version = ?`, so a stale delete affects zero rows and a concurrent flush cannot write `deleted_at = null` back | `main:shared/persistence/SoftDeletableEntity.java:20-27`; all seven `@SQLDelete` strings | `test:shared/persistence/SoftDeleteTest.deletingAStaleInstanceFailsInsteadOfSilentlyWinning`, `.aConcurrentUpdateCannotResurrectADeletedRow`; **`test:ArchitectureTests.softDeletableEntitiesDeclareTheirOwnHibernateAnnotations`** (reconstructs the expected SQL from each entity's own `@Table`) |
| FR-DLC-4 | free a soft-deleted row's unique key for reuse, by replacing every unique constraint on those tables with a **partial** unique index over live rows | `V17__soft_delete.sql:34-59` | `test:shared/persistence/SoftDeleteTest.aDeletedKeyIsFreeToUseAgain`; `test:organization/internal/OrgRbacApiTest.aDeletedRoleCodeCanBeMintedAgain`; `test:organization/internal/OrgRbacAuthorityTest.aRemovedMemberCanBeInvitedBackIntoTheSameOrganization` |
| FR-DLC-5 | make `SoftDeleteRecovery` the only way to see or restore a deleted row — native SQL confined to one class, table names taken from the entity's own `@Table` and never from caller input, restore rejected when the row is live or already restored, and uniqueness collisions left to surface as an integrity violation | `main:shared/persistence/SoftDeleteRecovery.java` | `test:shared/persistence/SoftDeleteTest.aDeletedRowCanBeFoundAgainAndRestored`, `.findDeletedIgnoresLiveRows`, `.restoreClearsTheDeletionStamp`, `.restoringALiveRecordIsRejected`, `.deletingTwiceIsRejected`, `.restoringOntoAKeyTakenWhileDeletedIsRejected` |
| FR-DLC-6 | hard-delete soft-deleted rows past `app.persistence.soft-delete.retention` (default **P30D**) nightly, in batches that commit independently | `main:scheduler/internal/SoftDeletePurgeJob.java:72-137` | `test:scheduler/internal/SoftDeletePurgeJobIntegrationTest.purgesAgedRowsFromEverySoftDeletableTable`, `.keepsRowsInsideTheWindowAndRowsThatWereNeverDeleted` |
| FR-DLC-7 | purge children before parents (`membership` before `org_role`), and cover **every** soft-deletable entity except those reached by an `on delete cascade` FK | `main:scheduler/internal/SoftDeletePurgeJob.java:36-65` | `test:scheduler/internal/SoftDeletePurgeJobIntegrationTest.purgeOrderCoversEverySoftDeletableEntity` (derived from the JPA metamodel), `.purgesAnAgedMembershipAndItsAgedRoleInOneRun` |
| FR-DLC-8 | skip an aged `org_role` still referenced by any membership, and isolate a per-table failure so one failing table cannot starve the tables behind it — logging it, continuing, and rethrowing the first failure at the end | `main:scheduler/internal/SoftDeletePurgeJob.java:85-86,112-137` | `test:scheduler/internal/SoftDeletePurgeJobIntegrationTest.aLiveMembershipPinningAnAgedRoleDoesNotStarveTheTablesBehindIt` |
| FR-DLC-9 | reject a negative retention, a batch size below 1 or a max-batches below 1 **at startup**, while allowing a zero retention (how tests and one-off erasures ask for "now") | `main:shared/persistence/SoftDeleteProperties.java` | none (validation itself is untested) |
| FR-DLC-10 | keep `audit_log`, `in_app_notification`, `webhook_delivery`, `role_permission` and the framework tables **out** of soft delete | `V17__soft_delete.sql:3-8` | `test:ArchitectureTests.softDeletableEntitiesDeclareTheirOwnHibernateAnnotations` (by exclusion); `test:audit/internal/AuditRecordingTest.theTrailSurvivesTheSoftDeleteOfWhatItDescribes` |
| FR-DLC-11 | soft-delete a role through an ordinary versioned update rather than `repository.delete`, because `@SQLDelete` overrides only the entity row's statement and Hibernate would still hard-delete the `role_permission` collection underneath it | `main:organization/internal/Role.java:96-109`; `main:organization/internal/RoleService.java:123-124` | `test:organization/internal/RoleSoftDeleteTest.deletingARoleHidesItWithoutTouchingItsPermissions`, `.restoringADeletedRoleReturnsItsOriginalPermissions` |
| FR-DLC-12 | filter soft-deleted rows manually in every non-JPA read path, since `@SQLRestriction` never applies to native SQL | `main:webhooks/internal/WebhookDeliveryQueue.java:80`; `main:analytics/internal/AnalyticsReport.java:22`; `main:identity/internal/UserRepository.java:21-23` | `test:webhooks/internal/WebhookDeliveryTest.aPendingDeliveryForADeletedSubscriptionIsNeverClaimed`; `test:analytics/internal/AnalyticsApiTest.softDeletedUsersAreExcludedFromTheReport` |
| FR-DLC-13 | apply Flyway migrations against the real database at startup and record them | `V1`–`V19`; `application.yaml:27-29` | `test:shared/persistence/FlywayBaselineTest.baselineMigrationAppliedAgainstRealPostgres` |

**Reachability.** `SoftDeleteRecovery` is injected by **no controller** — restore is a Java API only,
not reachable over HTTP. Neither `app_user` nor `organization` is ever deleted by application code
(no `.delete(`/`.deleteById(` call exists on `UserRepository` or `OrganizationRepository`, and
`OrganizationService` declares no `delete` method at all), although both are fully wired for soft
delete and appear in `PURGE_ORDER`; the read side (FR-IDN-4) is built for a write side that does not
yet exist. Two further states are enforced on the read path with no write path anywhere in `src/main`,
so each is reachable only by direct SQL: `ProvisioningStatus.DISABLED` (§3.2 — `User.disable()` has no
caller) and `MembershipStatus.SUSPENDED` (§3.3 — `Membership` has no suspend method, and `create` only
ever writes `ACTIVE`).

### 3.14 Audited impersonation (FR-IMP)

**The governing rule: this is the only sanctioned path from the platform axis to tenant data, and the
platform tier that authorized it does not travel into it.** §5.1's two axes never intersect, so an
operator investigating a tenant has exactly one way in — open a session and wear the account. The
session is authorized once, bounded by a server-set deadline, carries a stated reason, and names the
operator on every row it writes; a widened role would do none of that. Schema in
[DATA_MODEL.md](DATA_MODEL.md) §4.3.2 and §8; the contributor-facing invariants in
[../AGENTS.md](../AGENTS.md) §5.5.

The session module is `identity` (it owns the accounts a session names). The enforcing filter is in
`shared`, reaching back through the `shared.security.ImpersonationLookup` port — the same seam as
`OrgAuthorization`, and the reason `shared` still compile-depends on no business module.

| ID | The system SHALL … | Implementation | Verification |
|---|---|---|---|
| FR-IMP-1 | expose the operator surface at `POST` / `GET` / `DELETE /api/v1/admin/impersonations` with `platform-support` as the floor on all three | `main:identity/internal/ImpersonationController.java:68-94` | `test:identity/internal/ImpersonationApiTest.aTenantUserCannotOpenASession`, `.aSessionOpensReadOnlyForTheServerDefaultLifetime` |
| FR-IMP-2 | swap the request's effective principal when `X-Impersonate: <sessionId>` is present on an `/api/**` request, and leave both the chain and the security context untouched when it is absent | `main:shared/security/ImpersonationFilter.java:79-83,134-144` | `test:shared/security/ImpersonationFilterTest.theContextIsSwappedForTheChainAndRestoredAfterwards`, `.anAbsentHeaderLeavesBothTheChainAndTheContextAlone` |
| FR-IMP-3 | perform that swap at `@Order(-2)` — after authentication (−100) and **before** rate limiting (−1), idempotency (0) and the provisioning gate (1) — so the whole downstream request sees one effective principal | `main:shared/security/ImpersonationFilter.java:46` | `test:identity/internal/ImpersonationProvisioningGateTest.aTargetDisabledMidSessionIsRefused` (the gate evaluates the target, proving it runs after the swap) |
| FR-IMP-4 | give the impersonated principal an **empty** authority collection, so org permissions still resolve from the database for the target while every `hasRole('platform-*')` check fails — including on the endpoint that mints sessions | `main:shared/security/ImpersonatedAuthenticationToken.java:28`; `main:shared/security/CurrentUserProvider.java:75-86` | `test:identity/internal/ImpersonationReachTest.supportReachesTenantDataThroughASessionAndTheAdminSurfaceOnlyOutsideOne` |
| FR-IMP-5 | resolve a session by its id **and** the authenticated actor's subject, so a leaked session id is worthless to anyone but the operator it was issued to — including an operator holding a higher tier | `main:identity/internal/ImpersonationSessionRepository.java:21`; `main:shared/security/ImpersonationFilter.java:84-91` | `test:identity/internal/ImpersonationReachTest.aSessionIdPresentedByADifferentActorIsRejected` |
| FR-IMP-6 | treat a malformed, unknown, ended or expired id identically — **403 `FORBIDDEN`** in the envelope with `source.header = "X-Impersonate"`, never a 500 and never a distinguishing message | `main:identity/internal/ImpersonationLookupImpl.java:39-46`; `main:shared/security/ImpersonationFilter.java:147-150` | `test:identity/internal/ImpersonationReachTest.aMalformedSessionIdIsAForbiddenEnvelopeNotAServerError`; `test:shared/security/ImpersonationFilterTest.anUnresolvableSessionDeniesWithoutRunningTheChainOrTouchingTheContext` |
| FR-IMP-7 | permit only `GET`, `HEAD` and `OPTIONS` inside a `READ_ONLY` session, rejecting anything else before the endpoint is reached | `main:shared/security/ImpersonationFilter.java:58,129-132` | `test:shared/security/ImpersonationFilterTest.aReadOnlySessionPassesTheSafeMethodsAndRefusesTheRest`, `.aWriteCapableSessionPassesAnUnsafeMethod`; `test:identity/internal/ImpersonationReachTest.aReadOnlySessionRefusesAnUnsafeMethodAndNamesTheHeaderThatCausedIt` |
| FR-IMP-8 | restore the previous `SecurityContext` in a `finally`, so a pooled request thread never hands the next request someone else's identity | `main:shared/security/ImpersonationFilter.java:134-144` | `test:shared/security/ImpersonationFilterTest.theContextIsSwappedForTheChainAndRestoredAfterwards`; `test:identity/internal/ImpersonationReachTest.theRequestAfterAnImpersonatedOneSeesItsOwnIdentity` |
| FR-IMP-9 | re-check the **actor's** platform tier on every impersonated request — `platform-support` to hold any session, `platform-admin` for a write-capable one — rather than trusting the tier held at open time | `main:shared/security/ImpersonationFilter.java:96,123-128` | `test:identity/internal/ImpersonationReachTest.anOperatorWhoLosesTheirPlatformTierLosesTheSessionTheyHold`, `.demotingAWriteOperatorRefusesTheWriteCapableSession` |
| FR-IMP-10 | re-check **both** accounts' provisioning state on every impersonated request — independently of `app.provisioning.gate-enabled`, since a guarantee this feature is sold on cannot be borrowed from a switch another module owns | `main:identity/internal/ImpersonationLookupImpl.java:50-51,74-80` | `test:identity/internal/ImpersonationReachTest.disablingTheOperatorsOwnAccountDeniesTheVeryNextImpersonatedRequest`, `.deletingTheTargetMidSessionDeniesTheVeryNextImpersonatedRequest` |
| FR-IMP-11 | decide liveness on read (`ended_at is null and expires_at > now`) so that ending a session denies the **very next** request and an expired one denies with **no sweep job** and no `_expired` action | `main:identity/internal/ImpersonationSession.java:95-97`; `main:identity/internal/ImpersonationService.java:136-152` | `test:identity/internal/ImpersonationReachTest.endingASessionDeniesTheVeryNextRequest`, `.anExpiredSessionDeniesWithoutAnySweepJobHavingRun` (asserts `ended_at` stays null) |
| FR-IMP-12 | never write to the account a session wears: the provisioning gate uses `peek`, not `authorize`, so no lazy `INVITED → ACTIVE` fires on an operator's read | `main:identity/internal/ProvisioningGateFilter.java:96-104` | `test:identity/internal/ImpersonationProvisioningGateTest.aSessionNeverActivatesTheTargetItWears`, with `.aUsersOwnFirstRequestStillActivatesThem` as the control |
| FR-IMP-13 | refuse a target that has no `app_user`, is `DISABLED`, or is soft-deleted — and keep **404 and 409 distinct**, because a 404 invites re-provisioning the very account somebody erased | `main:identity/internal/ImpersonationService.java:249-260`; `main:identity/internal/UserRepository.java` (`existsDeletedBySubject`) | `test:identity/internal/ImpersonationApiTest.aDeletedTargetIsAConflictAndAnUnknownOneIsNotFound`, `.aDisabledTargetCannotBeImpersonated` |
| FR-IMP-14 | permit impersonating an account that itself holds **any** platform realm role only to `platform-superadmin`, resolving the target's **effective** realm roles from Keycloak's composite mapping (the set the target's token will carry) | `main:identity/internal/ImpersonationService.java:267-276`; `main:identity/internal/KeycloakUserAdminGateway.java` (`realmRoles` → `/role-mappings/realm/composite`) | `test:identity/internal/ImpersonationApiTest.onlyASuperadminMayImpersonateAnAccountHoldingAPlatformRole`; `test:identity/internal/KeycloakProvisioningIntegrationTest.realmRolesReportsRolesHeldThroughACompositeNotJustDirectMappings` (real Keycloak) |
| FR-IMP-15 | default `mode` to `READ_ONLY` and require `platform-admin` for `WRITE` — checked in the service, because the mode is in the body and an annotation cannot see it | `main:identity/internal/ImpersonationController.java:96-106`; `main:identity/internal/ImpersonationService.java:87-92` | `test:identity/internal/ImpersonationApiTest.aWriteCapableSessionRequiresPlatformAdmin` |
| FR-IMP-16 | require a `reason` of at least 8 characters after trim and at most 500, rejecting (never truncating) with 422 and `source.pointer = /data/attributes/reason` | `main:identity/internal/ImpersonationService.java:209-224` | `test:identity/internal/ImpersonationApiTest.aReasonMustBeGivenAndMustSayEnoughToReview` |
| FR-IMP-17 | bound the session lifetime server-side — default `app.impersonation.default-ttl` (PT15M), cap `max-ttl` (PT30M) — **rejecting** an over-cap request with 422 rather than silently clamping it, and refusing to start when the default exceeds the cap | `main:identity/internal/ImpersonationProperties.java`; `main:identity/internal/ImpersonationService.java:226-238` | `test:identity/internal/ImpersonationApiTest.aSessionOpensReadOnlyForTheServerDefaultLifetime`, `.theServerBoundsTheSessionLifetimeAndRefusesAnOverCapRequest` |
| FR-IMP-18 | refuse self-impersonation with 422 — it grants nothing but produces a trail that reads as oversight and records none | `main:identity/internal/ImpersonationService.java:199-205` | `test:identity/internal/ImpersonationApiTest.nobodyMayImpersonateThemselves` |
| FR-IMP-19 | hold at most **one live session per (actor, target)**: re-issuing supersedes the previous one and audits the supersede, a merely lapsed session is left untouched, and concurrent opens are serialised by a `pg_advisory_xact_lock` over the pair rather than a unique index (liveness depends on `expires_at`, which an index cannot express) | `main:identity/internal/ImpersonationService.java:169-187`; `main:identity/internal/ImpersonationSessionRepository.java:35-56` | `test:identity/internal/ImpersonationApiTest.reIssuingAgainstTheSameTargetSupersedesTheOldSessionAndAuditsBoth`, `.twoOpensInFlightForOnePairStillLeaveExactlyOneLiveSession`, `.reIssuingAfterASessionLapsedLeavesTheLapsedRowUntouched` |
| FR-IMP-20 | list an operator's own sessions — live and historical, newest first, cursor-paginated per §4.4 — and require `platform-admin` for `?actor=<subject>`, since who is being investigated is itself sensitive | `main:identity/internal/ImpersonationController.java:82-87`; `main:identity/internal/ImpersonationService.java:112-129` | `test:identity/internal/ImpersonationApiTest.theListingShowsOnlyTheCallersOwnSessionsAndPaginatesByCursor`, `.aPlatformAdminCanReviewAnotherOperatorsSessionsAndAPeerCannot` |
| FR-IMP-21 | let the opening operator **or** any `platform-admin` end a session immediately, keeping the ending one-way and idempotent (a second call must not move "when did the reach stop" or re-audit) | `main:identity/internal/ImpersonationService.java:136-152`; `main:identity/internal/ImpersonationSession.java:85-92` | `test:identity/internal/ImpersonationApiTest.onlyTheOperatorWhoOpenedASessionOrAPlatformAdminMayEndIt` |
| FR-IMP-22 | record `audit_log.actor` as the **accountable human** — the operator inside a session — with the worn identity in `on_behalf_of` and the session in `impersonation_id`, filled from the security context so the `AuditLog` port signature is unchanged and no call site can forget it | `main:audit/internal/AuditLogImpl.java:46-59`; `main:audit/internal/AuditEntry.java:33-37`; `V19__audit_log_impersonation.sql` | `test:identity/internal/ImpersonationReachTest.anAuditRowFromInsideASessionNamesTheOperatorAndTheIdentityTheyWore`, `.aWebhookCreatedInsideASessionNamesTheOperatorAndTheSession` |
| FR-IMP-23 | audit the session lifecycle as `platform.impersonation_started` / `_ended` / `_superseded` with a **null `org_id`**, keeping the requested org in the detail — writing an unvalidated `org_id` would let any `platform-support` operator post chosen text into an unrelated tenant's `GET /orgs/{id}/audit` feed | `main:identity/internal/ImpersonationService.java:169-187,150-151` | `test:identity/internal/ImpersonationApiTest.theLifecycleTrailIsPlatformScopedRatherThanFiledUnderTheRequestedOrg`, `.reIssuingAgainstTheSameTargetSupersedesTheOldSessionAndAuditsBoth` |
| FR-IMP-24 | surface `onBehalfOf` and `impersonationId` on the audit resource, so a reviewer can find every action taken inside someone else's account from the API alone | `main:audit/internal/AuditController.java:47-49,117-130` | `test:identity/internal/ImpersonationReachTest.anAuditRowFromInsideASessionNamesTheOperatorAndTheIdentityTheyWore` (its final assertions read `GET /api/v1/audit`) |
| FR-IMP-25 | make `app.impersonation.enabled=false` **refuse** rather than remove the feature: every route and the `X-Impersonate` header answer **403** naming the switch. The header must never be silently ignored — a dropped header succeeds as the operator while they believe they are wearing the target | `main:identity/internal/ImpersonationController.java` (`requireEnabled`); `main:shared/security/ImpersonationFilter.java` | `test:identity/internal/ImpersonationDisabledTest.theRouteRefusesInsteadOfDisappearing`, `.theListingAndTheEndpointThatEndsASessionRefuseToo`, `.theImpersonateHeaderIsRefusedRatherThanIgnored` |

**Deliberate non-checks, stated so nobody "fixes" them into existence.** `orgId` is **recorded, not
validated** against the target's memberships: it only becomes the session's active org, and
permissions still resolve from the database for the target, so an org the target does not belong to
grants exactly nothing — the same fail-closed answer a membership check would produce, without a
second copy of the org model living in `identity` (`ImpersonationService.java:74-78`). An **`INVITED`**
target is impersonable on purpose; the defect worth avoiding there was the *write* (FR-IMP-12), not
the reachability. And `currentSubject()` under a session returns the **target**, not the operator, so
rate-limit buckets, idempotency keys and `created_by` all describe the identity the request ran as —
`audit_log` is the single place that records who was answerable (FR-IMP-22).

---

### 3.15 Localization (FR-LOC)

The system SHALL:

| ID | Requirement | Where | Verified by |
|---|---|---|---|
| FR-LOC-1 | resolve a message key for a locale through the `Messages` port with the fallback chain exact tag → language → `app.localization.default-locale` → **the key itself** — a catalog gap renders, it never throws | `main:localization/internal/MessagesImpl.java` behind `main:localization/Messages.java` | `test:localization/internal/MessageResolutionTest.fallsBackExactThenLanguageThenDefaultThenKey` |
| FR-LOC-2 | store locales as lowercased BCP-47 tags and answer an unparseable tag with 422 (`source.parameter: locale`), never a 500 | `main:localization/internal/TranslationService.normalizeLocale` | `test:localization/internal/LocalizationApiTest.anUnparseableLocaleIsA422NotA500` |
| FR-LOC-3 | cache one bundle per locale (L1+L2) and evict + broadcast on every write and delete, so resolution reflects a change promptly cluster-wide | `main:localization/internal/TranslationBundles.java` (the separate bean §4.3 of AGENTS demands), `TranslationService` `@CacheEvict`s | `test:localization/internal/MessageResolutionTest.writesAndDeletesEvictTheCachedBundle` |
| FR-LOC-4 | gate writes on `platform-admin`, leave reads authenticated, and audit every change and delete with from→to state (`localization.translation_changed` / `_deleted`) | `main:localization/internal/{TranslationController,TranslationService}.java` | `test:localization/internal/LocalizationApiTest.aNonAdminCannotWrite`, `.deleteRemovesAndAuditsWithBeforeAndAfterState` |
| FR-LOC-5 | publish `TranslationChanged` on create/replace via the aggregate and **explicitly** on delete (a delete fires no `@DomainEvents`); an unchanged value is an idempotent no-op — no event, no audit row | `main:localization/internal/Translation.change`, `TranslationService.delete` | `test:localization/internal/LocalizationApiTest` (audit sequence) |
| FR-LOC-6 | apply `MessageFormat` arguments in the resolved locale | `main:localization/internal/MessagesImpl.resolve` | `test:localization/internal/MessageResolutionTest.argumentsAreFormattedIntoTheResolvedText` |

### 3.16 Search (FR-SRCH)

The system SHALL:

| ID | Requirement | Where | Verified by |
|---|---|---|---|
| FR-SRCH-1 | serve org-scoped full-text search at `GET /api/v1/orgs/{orgId}/search` gated on `org:read`, with the tenant cut applied **inside** the SQL, never after; platform-wide (null-org) rows are invisible to it | `main:search/internal/{SearchController,SearchQueryService}.java` | `test:search/internal/SearchApiTest.tenantSearchFindsOwnDocumentsAndNeverAnotherOrgs`, `.platformWideRowsAreInvisibleToTenantSearchButFoundByAdminSearch` |
| FR-SRCH-2 | serve platform search at `GET /api/v1/admin/search` (`platform-support`), reaching org and platform-wide rows, optionally narrowed by `org` | same | same |
| FR-SRCH-3 | rank with `ts_rank_cd` over `websearch_to_tsquery('simple', q)`, falling back to trigram `word_similarity` over titles when nothing token-matches; the cursor carries the mode so later pages never re-decide, and ranks travel as `float8` (the float4 text round-trip would repeat page 1) | `main:search/internal/SearchQueryService.java` (the cast's why-comment) | `test:search/internal/SearchApiTest.aPrefixThatMatchesNoTokenFallsBackToTrigramAndTheCursorKeepsTheMode` |
| FR-SRCH-4 | index idempotently: the `(entity_type, entity_id)` upsert plus the `EventInbox` guard make at-least-once redelivery produce exactly one document | `main:search/internal/{SearchIndexStore,SearchEventListeners}.java` | `test:search/internal/SearchApiTest.anEventRedeliveryDoesNotDuplicateTheDocument` |
| FR-SRCH-5 | answer 50 warm org-scoped queries over 100,000 documents with **p95 under 50 ms** on the reference container — measured, not asserted by adjective | `V22`'s GIN indexes + `main:search/internal/SearchQueryService.java` | `test:search/internal/SearchPerformanceTest.p95StaysUnderBudgetAcross100kDocuments` (prints the measured p50/p95) |
| FR-SRCH-6 | reject a blank or over-long `q` with 422 naming the parameter | `main:search/internal/SearchQueryService.requireQuery` | `test:search/internal/SearchApiTest.aBlankQueryIsA422NamingTheParameter` |

## 4. External interface requirements

### 4.1 The response envelope

Every JSON body produced by a `ug.co.smsone` handler is wrapped by
`main:shared/web/EnvelopeResponseBodyAdvice.java`; the envelope records live in `main:shared/web/`.
The wire shape:

| Member | Shape |
|---|---|
| top level | `data` XOR `errors`, plus `meta` and `links` |
| `data` | one resource object `{id, type, attributes}`, or an array of them |
| `errors[]` | `{id, status, code, title, detail, source}` |
| `errors[].source` | exactly one of `pointer` (body field), `parameter` (query), `header` |
| `meta` | `{requestId, timestamp, apiVersion, page?}` |
| `meta.page` | `{size, count, hasMore, nextCursor}` — collections only |
| `links` | `{self?, next?}` |

- `data` XOR `errors`; the null branch is omitted entirely (`spring.jackson.default-property-inclusion: non_null`).
- `ApiError.status` is a **string** (JSON:API mandate). `ApiError.id` is `"<requestId>-<n>"`, `n` from 1.
- `meta.apiVersion` is the constant `"1"` (`ApiMetaFactory.API_VERSION`), also the OpenAPI `info.version`.
- `links.first`, `links.prev` and `links.last` are **never populated** anywhere and are therefore always omitted.
- `links.next` is rebuilt from the request **path only** — any other query parameter (`action`,
  `from`, `to`, `org` on the audit endpoints) is dropped, so following the next link silently widens
  a filtered query.

A body is wrapped only if the converter is a Jackson converter, the body is not already an
`ApiResponse` or a `ProblemDetail`, and the negotiated content type is JSON-compatible. Consequently
un-enveloped: the 302 file download (no body), every `204` handler, and everything outside
`ug.co.smsone` (springdoc, actuator).

### 4.2 Error codes

The enum **name is the wire `code`**; renaming an entry is a breaking API change
(`main:shared/error/ErrorCode.java`).

| Code | HTTP | Title | Emitted by |
|---|---|---|---|
| `BAD_REQUEST` | 400 | Bad request | `GlobalExceptionHandler` default 4xx mapping |
| `UNAUTHORIZED` | 401 | Authentication required | `ApiAuthenticationEntryPoint`; `UnauthorizedException` |
| `FORBIDDEN` | 403 | Access denied | `ApiAccessDeniedHandler` (filter chain); `GlobalExceptionHandler.handleAccessDenied` (method security); `ForbiddenException` (escalation guard, system-role immutability, file namespace) |
| `ACCOUNT_NOT_PROVISIONED` | 403 | Account not provisioned | `ProvisioningGateFilter` **only** |
| `ACCOUNT_DISABLED` | 403 | Account disabled | `ProvisioningGateFilter` **only** |
| `RESOURCE_NOT_FOUND` | 404 | Resource not found | `NotFoundException`; framework 404 |
| `METHOD_NOT_ALLOWED` | 405 | Method not allowed | framework (the `Allow` header is preserved) |
| `CONFLICT` | 409 | Conflict | `ConflictException`; idempotency in-progress and payload-mismatch |
| `PAYLOAD_TOO_LARGE` | 413 | Payload too large | `IdempotencyFilter` **only** |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Unsupported media type | framework |
| `VALIDATION_FAILED` | 422 | Validation failed | bean validation, `ConstraintViolationException`, `ValidationException`, cursor/page-size rejection |
| `RATE_LIMITED` | 429 | Too many requests | `RateLimitFilter` **only** |
| `SERVICE_UNAVAILABLE` | 503 | Service temporarily unavailable | `GlobalExceptionHandler.handleCircuitOpen` — a tripped circuit breaker (retry later), logged without a stack trace |
| `INTERNAL_ERROR` | 500 | Internal server error | catch-all handler; `ProvisioningGateFilter`'s own failure path |

Field-level validation errors additionally carry a per-constraint code of the form
`VALIDATION_<CONSTRAINT>` (e.g. `@NotBlank` → `VALIDATION_NOT_BLANK`) with `source.pointer`.

**One caveat a client must handle.** The two 403 details differ by the layer that denied — the
filter chain says "…access this resource.", method security says "…perform this operation.".
(The former second caveat — a framework-raised 413/429 rendering `code == "BAD_REQUEST"` — was
fixed in the 2026-08-01 audit remediation: `mapStatus` now maps 413, 429 and 503 to their codes.)

### 4.3 RFC 9457 negotiation

When the request's `Accept` header **contains** the substring `application/problem+json`, errors are
rendered as a `ProblemDetail` instead: `type` (`about:blank`), `status`, `title` (the `ErrorCode`
title), `detail` (the single error's detail, or `"<n> validation errors — see the errors extension."`),
plus extensions `code` and `requestId`, and `errors` when there is more than one error or exactly one
carrying a `source`. This is a plain substring test in both implementations
(`GlobalExceptionHandler:161`, `EnvelopeErrorWriter:36`) — not content negotiation — so
`Accept: */*, application/problem+json;q=0.1` flips the shape. Errors raised outside MVC (401, 403
from the chain, rate limiting, the provisioning gate) go through `EnvelopeErrorWriter`, whose
problem+json branch carries a **`source`** extension (singular) rather than `errors`.

### 4.4 Cursor pagination

Wire parameters: **`page[size]`** and **`page[after]`**, read from those literal names only.

| Property | Value |
|---|---|
| Default size | 20 |
| Maximum size | 100 — **rejected** above, never clamped |
| Cursor encoding | `property=<type>:<value>` pairs joined by `|`, base64url **without padding**; type tags `t` Instant, `u` UUID, `l` Long, `s` String |
| Sort | `createdAt desc, id desc` on every paginated collection |
| Totals | none, by design — no `COUNT`, no `totalPages`, no offsets |

Response shape:

```json
{
  "data": [ { "id": "…", "type": "…", "attributes": { } } ],
  "meta": { "requestId": "…", "timestamp": "…", "apiVersion": "1",
            "page": { "size": 20, "count": 20, "hasMore": true, "nextCursor": "…" } },
  "links": { "self": "/api/v1/settings", "next": "/api/v1/settings?page[size]=20&page[after]=…" }
}
```

**Paginated (8):** `GET /api/v1/admin/users`, `/api/v1/audit`, `/api/v1/orgs/{orgId}/audit`,
`/api/v1/orgs/{orgId}/members`, `/api/v1/notifications`, `/api/v1/settings`, `/api/v1/feature-flags`,
`/api/v1/orgs/{orgId}/webhooks/{id}/deliveries`.
**Deliberately un-paginated (5):** `GET /api/v1/permissions`, `/api/v1/orgs/{orgId}/roles`,
`/api/v1/orgs/{orgId}/webhooks`, `/api/v1/scheduler/locks`, `/api/v1/analytics/reports` — each
returns a bare list wrapped as `data: [...]` with no `meta.page`.

### 4.5 Request headers

| Header | Direction | Contract |
|---|---|---|
| `Authorization: Bearer <jwt>` | in | Required on everything except the public paths in FR-HTTP-31 |
| `X-Request-Id` | in / out | Accepted if `^[A-Za-z0-9_-]{1,64}$`, else replaced by a minted ULID. **Always** set on the response |
| `X-Correlation-Id` | in | Fallback source for the request id, same validation |
| `Idempotency-Key` | in | `^[A-Za-z0-9_-]{1,128}$`; honoured on `POST`/`PUT`/`PATCH` under `/api/` only |
| `Idempotency-Replayed: true` | out | Present only on a replayed response |
| `X-Impersonate` | in | An impersonation session id (§3.14). Honoured on `/api/**` only, and only for the operator the session was issued to; anything unresolvable is 403 with `source.header = "X-Impersonate"`. Ignored entirely when `app.impersonation.enabled=false`. There is **no** corresponding response header — a client that sent one knows it did |
| `Accept: application/problem+json` | in | Switches error rendering to RFC 9457 |
| `RateLimit-Policy`, `RateLimit` | out | draft-ietf structured fields: `"<tier>";q=<limit>;w=<window>` and `"<tier>";r=<remaining>;t=<reset>` |
| `X-RateLimit-Limit` / `-Remaining` / `-Reset` | out | Legacy equivalents, always set alongside |
| `Retry-After` | out | Seconds, on 429 only |
| `X-Webhook-Event`, `X-Webhook-Delivery`, `X-Webhook-Signature` | **outbound** | Set by the webhook sender on each delivery; signature is `sha256=<lowercase hex HMAC-SHA256>` |

#### 4.5.1 The outbound webhook payload

This is the body a subscriber's receiver must parse, and **the exact bytes over which the signature is
computed**. It is built by hand in `main:webhooks/internal/WebhookPayload.java:37-52` — not by a JSON
mapper — precisely so the signed bytes and the sent bytes cannot diverge with mapper configuration. It
is published nowhere else: there is no catalogue endpoint, and the OpenAPI spec does not carry the
event vocabulary either (§4.8, item 5).

```json
{
  "event": "org.member.added",
  "orgId": "3f1c…-…-…",
  "occurredAt": "2026-07-31T09:14:02.117Z",
  "data": { "subject": "…", "role": "OWNER" }
}
```

- `event` — one of the five codes in FR-WHK-1; identical to the `X-Webhook-Event` header.
- `orgId` — the Keycloak organization id, as a string.
- `occurredAt` — the domain event's own instant, `Instant.toString()` (ISO-8601, UTC, `Z`).
- `data` — a flat object. **Every value is a JSON string**, without exception: the builder holds a
  `Map<String, String>` (`WebhookPayload.java:18`), so a UUID or an enum is emitted quoted, never as a
  number or bare token. Key order is insertion order (`LinkedHashMap`).
- A **null value is dropped**, not serialized: `with(key, value)` skips a null
  (`WebhookPayload.java:30-35`), so an absent field is simply missing from `data`. `data` may
  legitimately be `{}`. A receiver must treat "key absent" and "value null" as the same case.

Per-event `data` keys, from `main:webhooks/internal/WebhookEventListener.java`:

| `event` | `data` keys | Source |
|---|---|---|
| `org.member.added` | `subject`, `role` (the role **code**; dropped if the event carries none) | `:31-33` |
| `org.member.removed` | `subject` | `:42-43` |
| `org.member.role_changed` | `subject` — **the new role is not carried** | `:52-53` |
| `org.role.permissions_changed` | `roleId` — the role id only; **the permissions are not carried** | `:62-63` |
| `org.status_changed` | `status` (`ACTIVE` \| `SUSPENDED`) | `:72-73` |

Two of the five are therefore notifications-to-refetch rather than state transfers: a receiver that
needs the new role or the new permission set must call back into the API.

### 4.6 Endpoint catalogue

Authority column: **"authenticated"** means a valid token plus a provisioned, non-disabled account —
no `@PreAuthorize`. Every endpoint can additionally return 401, 403 `ACCOUNT_NOT_PROVISIONED`,
403 `ACCOUNT_DISABLED`, 429 and 500.

| Method | Path | Required authority | Success | Notable errors |
|---|---|---|---|---|
| GET | `/api/v1/me` | authenticated **(exempt from the provisioning gate)** | 200 | 401 |
| GET | `/api/v1/admin/users` | `platform-support` | 200 (paged) | — |
| POST | `/api/v1/admin/impersonations` | `platform-support` (`mode=WRITE` needs `platform-admin`) | **201** | 403 write mode / target holds a platform role, 404 unknown target, 409 target deleted or `DISABLED`, 422 blank or short `reason` / self / over-cap `ttl` / bad `mode` |
| GET | `/api/v1/admin/impersonations` | `platform-support`; `?actor=<subject>` needs `platform-admin` | 200 (paged) | 403 another operator's sessions |
| DELETE | `/api/v1/admin/impersonations/{id}` | the opening operator, or `platform-admin` | **204** | 403, 404 |
| GET | `/api/v1/permissions` | authenticated | 200 (list) | — |
| POST | `/api/v1/orgs` | `platform-admin` | **201** | 409 duplicate alias (local **or** Keycloak), 422 alias not a lowercase slug `^[a-z0-9][a-z0-9-]{0,118}[a-z0-9]$` / blank name / non-email owner |
| GET | `/api/v1/orgs/{orgId}` | `org:read` | 200 | 404 |
| PATCH | `/api/v1/orgs/{orgId}` | `org:update` | 200 | 404, 422 |
| POST | `/api/v1/orgs/{orgId}/suspend` | `platform-admin` | 200 | 404 |
| POST | `/api/v1/orgs/{orgId}/reactivate` | `platform-admin` | 200 | 404 |
| GET | `/api/v1/orgs/{orgId}/members` | `member:read` | 200 (paged) | — |
| POST | `/api/v1/orgs/{orgId}/members` | `member:invite` | **201** (also on a no-op re-invite) | 403 escalation, 404 unknown role, 422 |
| PUT | `/api/v1/orgs/{orgId}/members/{subject}/role` | `member:role:assign` | 200 | 403 escalation, 404, **409 last owner** |
| DELETE | `/api/v1/orgs/{orgId}/members/{subject}` | `member:remove` | **204** | 404, **409 last owner** |
| GET | `/api/v1/orgs/{orgId}/roles` | `role:read` | 200 (list, un-paged) | — |
| GET | `/api/v1/orgs/{orgId}/roles/{roleId}` | `role:read` | 200 | 404 |
| POST | `/api/v1/orgs/{orgId}/roles` | `role:create` | **201** | 403 escalation, 409 reserved/duplicate, 422 unknown permission / empty `permissions` / `PLATFORM` prefix / code not matching `^[A-Za-z][A-Za-z0-9_]{1,62}$` |
| PUT | `/api/v1/orgs/{orgId}/roles/{roleId}` | `role:update` | 200 | 403 escalation / system role, 404, 422 unknown permission / empty `permissions` / blank name |
| DELETE | `/api/v1/orgs/{orgId}/roles/{roleId}` | `role:delete` | **204** | 403 system role, 404, 409 role still assigned |
| GET | `/api/v1/orgs/{orgId}/audit` | `audit:read` | 200 (paged; `from`/`to` bound `occurred_at` — `>= from`, `< to` — while the sort and cursor key on `created_at desc, id desc`) | 422 bad instant |
| GET | `/api/v1/audit` | `platform-support` | 200 (paged; same `occurred_at` window / `created_at` ordering split) | 422 bad instant |
| POST | `/api/v1/orgs/{orgId}/webhooks` | `webhook:manage` | **201** (secret in full, once) | 422 unsafe URL / unknown event |
| GET | `/api/v1/orgs/{orgId}/webhooks` | `webhook:manage` | 200 (list, un-paged; secret masked) | — |
| GET | `/api/v1/orgs/{orgId}/webhooks/{id}` | `webhook:manage` | 200 (secret masked) | 404 |
| PUT | `/api/v1/orgs/{orgId}/webhooks/{id}` | `webhook:manage` | 200 (secret masked). Also sets `status`: an **omitted, null or blank** `status` is read as `ACTIVE`, so a `PUT` that does not mention it **re-enables** a `DISABLED` subscription | 404, 422 unsafe URL / unknown event / empty `events` / unparseable `status` (`source.pointer = /data/attributes/status`) |
| DELETE | `/api/v1/orgs/{orgId}/webhooks/{id}` | `webhook:manage` | **204** | 404 |
| GET | `/api/v1/orgs/{orgId}/webhooks/{id}/deliveries` | `webhook:manage` | 200 (paged; survives subscription delete) | 404 |
| GET | `/api/v1/settings` | authenticated | 200 (paged) | — |
| GET | `/api/v1/settings/{key}` | authenticated | 200 | 404 |
| PUT | `/api/v1/settings/{key}` | `platform-admin` | 200 (upsert — never 201) | 422 blank value |
| GET | `/api/v1/feature-flags` | authenticated | 200 (paged) | — |
| GET | `/api/v1/feature-flags/{key}` | authenticated | 200 | 404 |
| PUT | `/api/v1/feature-flags/{key}` | `platform-admin` | 200 (upsert) | 422 missing `enabled` |
| GET | `/api/v1/translations` | authenticated | 200 (paged) | 422 bad `locale` filter |
| GET | `/api/v1/translations/{locale}/{key}` | authenticated | 200 | 404, 422 bad locale |
| PUT | `/api/v1/translations/{locale}/{key}` | `platform-admin` | 200 (upsert) | 422 bad locale / blank `value` |
| DELETE | `/api/v1/translations/{locale}/{key}` | `platform-admin` | **204** | 404, 422 bad locale |
| GET | `/api/v1/orgs/{orgId}/search` | `org:read` | 200 (paged, ranked) | 422 blank/over-long `q` or foreign cursor |
| GET | `/api/v1/admin/search` | `platform-support` | 200 (paged, ranked) | 422 |
| GET | `/api/v1/notifications` | authenticated (scoped to caller's subject) | 200 (paged) | — |
| POST | `/api/v1/notifications/{id}/read` | authenticated (scoped) | 200 | 404 (also when it is another user's) |
| POST | `/api/v1/files` (multipart `file`) | authenticated | **201** | 422 empty / unreadable |
| GET | `/api/v1/files/{*key}` | owner, or `platform-support` | **302** + `Location` (no body) | 403 foreign namespace, 404 |
| DELETE | `/api/v1/files/{*key}` | owner, or `platform-admin` | **204** | 403, 404 |
| POST | `/api/v1/files/presign` | authenticated (PUT always own namespace); GET needs owner or `platform-support` | 200 | 403, 404, 422 bad operation/key |
| GET | `/api/v1/scheduler/locks` | `platform-support` | 200 (list, un-paged) | — |
| GET | `/api/v1/analytics/reports` | `platform-support` | 200 (list, un-paged) | — |
| GET | `/api/v1/analytics/reports/{code}` | `platform-support` | 200 | 404 unknown report |
| GET | `/actuator/health`, `/health/liveness`, `/health/readiness`, `/actuator/info` | **public** | 200 | details never shown |
| GET | `/v3/api-docs*`, `/swagger-ui/**` | **public** | 200 | — |

**Absent by design or by gap:** no `DELETE /api/v1/settings/{key}`, no
`DELETE /api/v1/feature-flags/{key}`, no organization delete, no user disable, no soft-delete restore
endpoint, no webhook event-type catalogue, no job-trigger endpoint.

**Unreachable from inside an impersonation session:** every row above whose authority column names a
`platform-*` tier, including the three impersonation routes themselves — the impersonated principal
holds no authority at all (§3.14, FR-IMP-4). The org-permission rows are the ones a session *can*
reach, which is the entire point of the feature.

### 4.7 Integrations

| Integration | Configuration | Contract the system depends on |
|---|---|---|
| **OIDC / Keycloak (resource server)** | `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `.audiences` | JWKS at the issuer; claims `sub`, `preferred_username`, `email`, `realm_access.roles`, `resource_access.<client>.roles`, and the alias-keyed `organization` claim (`{"acme":{"id":"…"}}`) from the optional `organization` client scope. Audience must include `smsone-api`. |
| **Keycloak Admin API** | `app.keycloak-admin.{base-url, realm, client-id, client-secret, connect-timeout, read-timeout}` | Self-managed `client_credentials` token cache (30 s refresh skew) with a single 401 retry after invalidation. Calls: `GET/POST /users`, `GET /users/{id}/credentials`, `PUT /users/{id}/execute-actions-email`, `GET /roles/{name}`, `POST/GET /users/{id}/role-mappings/realm`, `POST/GET /organizations`, `POST/DELETE /organizations/{id}/members`. The service account needs `manage-organizations`, `view-organizations`, `query-organizations`, `manage-users`, `view-users` **and `view-realm`** — missing `view-realm` fails role assignment with a 403 that looks like a user-permission problem. Two wire quirks are pinned by test: the member body is a **bare JSON string**, and Keycloak's org `search` matches name/domain, not alias. |
| **S3 / SeaweedFS** | `app.storage.{endpoint, region, access-key, secret-key, bucket, bootstrap-bucket}` | AWS SDK v2 with `endpointOverride`, explicit region and **path-style access**. 5 MiB multipart parts. Presigned GET/PUT with a 10-minute TTL. Works verbatim against AWS/R2/B2 by swapping endpoint and credentials. |
| **SMTP** | `spring.mail.*`, `app.notification.from` | Plain `SimpleMailMessage`. Connect/read/write timeouts are pinned at 5000 ms each because JavaMail defaults to infinite and must stay well below the delivery `stale-lock`. |
| **Valkey (Redis protocol)** | `spring.data.redis.{host, port, timeout, connect-timeout}`, `app.cache.*`, `app.rate-limit.*` | Two independent uses: (a) cache L2 via `RedisCacheManager` plus a pub/sub topic `smsone:cache:invalidations` carrying `instanceId\ncacheName[\nkey]`; (b) Bucket4j token buckets over a Lettuce client with a 10-minute bucket TTL. Command **and** connect timeouts are pinned (2 s cache, 250 ms rate limit) so an outage degrades rather than stalls. |
| **OTLP** | **none in `application.yaml`** — Boot defaults apply (`localhost:4318`, matching the Compose port mapping) | Traces, metrics and logs are entirely framework-produced. The test profile disables export. `make run` exports no `OTEL_*` variable, so a remapped OTLP port in `docker/.env` is silently not honoured. |

### 4.8 OpenAPI

The spec is exported to `docs/openapi/openapi.{json,yaml}` (OpenAPI **3.1.0**) by
`./gradlew exportOpenApi` and also refreshed by the ordinary build. `main:shared/web/OpenApiConfig.java`
declares two globally-applied security schemes — `bearerAuth` (HTTP bearer, JWT) and `keycloak`
(OAuth2 authorization code with PKCE, URLs derived from the issuer) — three servers, each an
overridable `@Value` (`OPENAPI_LOCAL_URL`, default `http://localhost:8080`; `OPENAPI_STAGING_URL`,
default `https://staging-api.smsone.co.ug`; `OPENAPI_PROD_URL`, default `https://api.smsone.co.ug` —
`main:shared/web/OpenApiConfig.java:42-44`) — an
`X-Request-Id` response header on every operation, and an `OperationCustomizer` keyed on the
handler's **parameter type** that replaces the auto-generated object-shaped `page` parameter with the
real `page[size]` (integer, 1–100, default 20) and `page[after]` (string), then removes the orphaned
schema.

**Divergences a client generator must know about** (all verified against `docs/openapi/openapi.json`):

1. The spec documents **only success responses**. No error status, no `ApiResponse`/`ApiError`/
   `ProblemDetail` schema, and no envelope wrapper appear anywhere — the spec describes the
   **unwrapped** payload even though the advice always wraps it.
2. All four `/api/v1/files*` operations document a **required query parameter `user`** with a
   `CurrentUser` schema. springdoc cannot see through `CurrentUserArgumentResolver`, and unlike
   `CursorPageRequest` nothing corrects it.
3. `GET /api/v1/files/{key}` is documented as 200; the handler returns **302** with a `Location`.
4. `POST /api/v1/files` is documented as `application/json`; the handler is `multipart/form-data`.
5. `CreateWebhookRequest.events` is a bare string array with no enum, so the subscribable vocabulary
   is undiscoverable from the spec.
6. **`X-Impersonate` is undocumented.** The three `/api/v1/admin/impersonations` operations are in the
   spec (`openapi.yaml:700,1302`), but the header that *uses* a session appears on no operation:
   `OpenApiConfig` adds only the `X-Request-Id` response header and the two cursor parameters. A
   generated client can open and end a session but cannot send one. Closing this is a customizer in
   `OpenApiConfig`, not a documentation edit — see §3.14, FR-IMP-2.

`springdoc.paths-to-exclude: /test/**` keeps the test-classpath contract controller out of the spec;
it is not a security exclusion — `/test/**` still requires a token.

---

## 5. Non-functional requirements

### 5.1 Security and authorization (NFR-SEC)

#### 5.1.1 The two axes — stated explicitly

Authorization has **two disjoint axes**. Nothing bridges them.

| | **Platform axis** | **Organization axis** |
|---|---|---|
| Subject of the grant | The person, platform-wide | The person **in one organization** |
| Carrier | Keycloak **realm role** in the token | `membership` row → `org_role` → `role_permission`, in the database |
| Vocabulary | 3 hierarchical tiers (`platform-superadmin` > `platform-admin` > `platform-support`) | 15 flat permission codes |
| Expression | `@PreAuthorize("hasRole('platform-…')")` | `@PreAuthorize("hasPermission(#orgId, 'organization', '<code>')")` |
| Evaluator | `RoleHierarchy` + `DefaultMethodSecurityExpressionHandler` | `ApiPermissionEvaluator` → `OrgAuthorization` → `PermissionResolver` |
| Scope | Global | Exactly one organization — the one the token is scoped to |

**A platform role grants zero organization permissions.** `ApiPermissionEvaluator` has no role
branch: a `platform-superadmin` calling `GET /api/v1/orgs/{orgId}/members` is denied exactly as any
stranger is. **An organization permission grants zero platform authority**: the permission catalog
contains no platform code, and `ProvisioningProperties` refuses to start if the provisioning baseline
role is a platform role, because invite is reachable by any org member holding `member:invite`.

Reaching tenant data as a platform operator is impersonation's job — **shipped, §3.14** — and it is
the only supported path. It is audited, time-boxed, reason-bearing, and carries no platform authority
of its own, which is exactly what a widened role would not.

| ID | Requirement | Verification |
|---|---|---|
| NFR-SEC-1 | The two axes SHALL remain disjoint; no platform role SHALL bypass a permission check and no permission SHALL confer platform authority | `test:organization/internal/OrgRbacApiTest.crossOrgAccessIsDeniedBeforeAnyDbHit`; `test:identity/internal/ProvisioningPropertiesTest` |
| NFR-SEC-2 | Every request SHALL be authenticated by a JWT validated against the configured issuer **and audience**; there is no session and no CSRF token | `test:shared/security/KeycloakIntegrationTest`; audience rejection has **no test** |
| NFR-SEC-3 | Platform tiers SHALL be hierarchical upward only, and the hierarchy SHALL be applied identically by method security and by `CurrentUser.hasRole` | `test:shared/security/PlatformRoleHierarchyTest.theLadderDoesNotRunDownwards`; `test:files/internal/FileApiTest.superadminInheritsTheDeleteTier` |
| NFR-SEC-4 | A **client** role SHALL never satisfy a **realm** role check | `test:shared/security/KeycloakJwtAuthenticationConverterTest.clientRoleNamedPlatformAdminDoesNotBecomeRealmPlatformAdmin` |
| NFR-SEC-5 | Authorization SHALL **default-deny**: an unwired port, an absent principal, a token with zero or multiple organizations, a suspended org, a non-active membership and an unloadable role each resolve to no permission | `test:organization/internal/OrgRbacApiTest.tokenWithNoActiveOrgIsDenied`; `test:organization/internal/OrgRbacAuthorityTest` (four cases) |
| NFR-SEC-6 | A caller SHALL NOT be able to grant a permission they do not hold, on any of the four grant paths | `test:organization/internal/OrgRbacApiTest.aCallerCannotGrantAPermissionItDoesNotHold`, `.aNonOwnerCannotSelfPromoteToOwner`, `.aNonOwnerCannotInviteAnOwner` |
| NFR-SEC-7 | The application SHALL NOT store, transmit, log or otherwise handle any password or credential value | §6.4; `test:identity/internal/KeycloakProvisioningIntegrationTest` |
| NFR-SEC-8 | No stack trace, framework message, exception class or binding detail SHALL reach a client, in either response shape | `test:shared/web/EnvelopeContractTest.neverLeaksStackTracesOrInternalDetails`; `test:shared/error/ProblemDetailContractTest.catchAll500NeverLeaksInProblemJsonEither` |
| NFR-SEC-9 | Every caller-supplied outbound URL SHALL be SSRF-guarded against loopback, private, link-local, CGNAT, ULA, special-purpose and NAT64-embedded-private addresses, at configure time and again at send time — **subject to two configuration opt-outs**, `app.webhooks.allow-private-hosts` and `app.notification.webhook-allow-private-hosts` (both default **false**), either of which disables address validation entirely for its module | `test:shared/http/SafeOutboundUrlTest` (six tests, including `.allowPrivateHostsBypassesTheAddressCheck`) |
| NFR-SEC-10 | A webhook signing secret SHALL be returned in full **once**, at creation, and masked on every subsequent read | `test:webhooks/internal/WebhookApiTest.createReturnsTheSecretOnceThenReadsMaskIt` |
| NFR-SEC-11 | Rate-limit bucket keys SHALL be redacted before logging, retaining only `prefix:tier:type` | none |
| NFR-SEC-12 | An inbound request id SHALL be validated against `^[A-Za-z0-9_-]{1,64}$` before entering the log context, to prevent log forging | `test:shared/web/EnvelopeContractTest.replacesMalformedInboundRequestId` |
| NFR-SEC-13 | Actuator SHALL expose only `health` and `info`, with `show-details: never` | `test:shared/security/SecurityContractTest.healthProbesArePublic` |
| NFR-SEC-14 | An org role code SHALL NOT be able to impersonate the platform vocabulary (`PLATFORM*` prefix rejected) — a naming guard, explicitly **not** a privilege boundary | `test:organization/internal/OrgRbacApiTest.anOrgRoleCodeCannotBorrowThePlatformVocabulary` |
| NFR-SEC-15 | A platform operator SHALL reach tenant data only through an impersonation session (§3.14), and the platform tier that authorized the session SHALL NOT travel into it | `test:identity/internal/ImpersonationReachTest.supportReachesTenantDataThroughASessionAndTheAdminSurfaceOnlyOutsideOne` |
| NFR-SEC-16 | A session id SHALL NOT function as a bearer token: it is resolved against the authenticated actor's own subject, so a leaked id grants its holder nothing — including a holder of a *higher* tier | `test:identity/internal/ImpersonationReachTest.aSessionIdPresentedByADifferentActorIsRejected` |
| NFR-SEC-17 | A swapped `SecurityContext` SHALL be restored before the request thread returns to the pool, and every gate a session passed SHALL be re-decided on the next request rather than trusted from open time | `test:shared/security/ImpersonationFilterTest.theContextIsSwappedForTheChainAndRestoredAfterwards`; `test:identity/internal/ImpersonationReachTest.theRequestAfterAnImpersonatedOneSeesItsOwnIdentity`, `.endingASessionDeniesTheVeryNextRequest`, `.anOperatorWhoLosesTheirPlatformTierLosesTheSessionTheyHold` |

**Residual security gaps** (present in code, stated so a fork can close them):

- **No CORS configuration exists.** A browser client on another origin will fail.
- **No negative audience test.** The control exists and is configured; nothing proves rejection.
- **Every platform setting and feature flag is readable by any authenticated, provisioned user** —
  the four GET endpoints carry no authority check. Unlike `GET /api/v1/permissions`, whose javadoc
  states the global read is deliberate, nothing records this as intentional.
- **Webhook subscription mutations are unaudited**, including secret creation and rotation.
- **Webhook secrets are stored in plaintext.**
- **The SSRF guard can be switched off by configuration, in two independent places.**
  `app.webhooks.allow-private-hosts` (`application.yaml:174`, default `false`) and
  `app.notification.webhook-allow-private-hosts` (no `application.yaml` entry; defaulted to `false` in
  `NotificationProperties`' compact constructor) are each passed straight into
  `SafeOutboundUrl.requireSafe`, which returns after the scheme/host check and **never resolves the
  host** when the flag is true (`main:shared/http/SafeOutboundUrl.java:46-48`). Setting either to true
  makes loopback, RFC-1918, link-local and cloud-metadata targets acceptable — at configure time
  **and** at send time — for that module's outbound calls. Both are `true` in the test profile. A fork
  should treat them as dev-only and assert they are false in any production configuration.
- `SafeOutboundUrl` documents a residual **DNS-rebinding** window; a network egress policy is required.

### 5.2 Multi-tenancy isolation (NFR-TEN)

| ID | Requirement | Verification |
|---|---|---|
| NFR-TEN-1 | The tenant key SHALL be the Keycloak organization id everywhere — on the wire, in `org_id` columns, and in cache keys — never the local surrogate key | `test:organization/internal/OrgRbacApiTest` (all org calls) |
| NFR-TEN-2 | A request SHALL be denied unless the target organization id **string-equals** the caller's single active organization, checked before any database access, with no alias branch | `test:organization/internal/OrgRbacApiTest.crossOrgAccessIsDeniedBeforeAnyDbHit` |
| NFR-TEN-3 | Multi-tenant reads SHALL filter by the tenant key **in the query**, not after loading, as defence in depth below the annotation | `test:organization/internal/OrgRbacAuthorityTest.permissionsAreScopedToTheOwningOrganization`; `test:webhooks/internal/WebhookApiTest.theDeliveryLogOutlivesTheSubscriptionButStaysTenantScoped` |
| NFR-TEN-4 | Suspending an organization SHALL immediately revoke every member's permissions, with the status check inside the cached value and eviction on the status-change event | `test:organization/internal/OrgRbacApiTest.suspendIsPlatformAdminOnlyAndCutsMemberAccess`; `test:organization/internal/OrgRbacAuthorityTest.suspendedOrganizationGrantsNothingUntilReactivated` |
| NFR-TEN-5 | Permission cache entries SHALL be keyed `<orgId>:<subject>` so no entry can be shared across tenants | `test:organization/internal/OrgRbacAuthorityTest.permissionCacheIsEvictedAfterAMembershipRoleChange` |
| NFR-TEN-6 | Per-user resources (files, in-app notifications) SHALL be scoped by the caller's **subject**, and a foreign resource SHALL yield 403 (files) or 404 (notifications — no existence disclosure) | `test:files/internal/FileApiTest.aCallerCannotDownloadAnotherUsersFile` |
| NFR-TEN-7 | Rate-limit buckets SHALL be per tenant on TENANT-scoped tiers, so one tenant cannot exhaust another's quota | `test:shared/ratelimit/RateLimitIntegrationTest.edgeFilterKeysByActiveOrgFromTheOrganizationClaim` |
| NFR-TEN-8 | Idempotency keys SHALL be namespaced per principal, so no caller can replay or squat another's key | `test:shared/idempotency/IdempotencyIntegrationTest.keysAreScopedPerPrincipal` |

**Known coarse behaviour:** the permission cache evictor performs a full `cache.clear()` of
`org-permissions` rather than a keyed evict, so one tenant's membership change invalidates every
tenant's cached permissions. This is a performance property, not an isolation defect.

### 5.3 Auditability (NFR-AUD)

| ID | Requirement | Verification |
|---|---|---|
| NFR-AUD-1 | Every audited change SHALL record who (subject), when (`occurred_at` + `created_at`), where (`org_id`, null for platform-level), what (`action`, `target`) and from→to state | `test:audit/internal/AuditRecordingTest.settingChangeRecordsWhoWhatAndFromToState` |
| NFR-AUD-2 | The audit row SHALL commit or roll back **atomically with the change it describes**, because `AuditLog.record` runs inside the caller's transaction | `test:audit/internal/AuditRecordingTest` |
| NFR-AUD-3 | `audit_log` SHALL be append-only: never soft-deletable, never updated, and surviving the deletion of its subject | `test:audit/internal/AuditRecordingTest.theTrailSurvivesTheSoftDeleteOfWhatItDescribes` |
| NFR-AUD-4 | Attribution SHALL use the immutable subject in **all** durable stores — `audit_log.actor`, `created_by`/`updated_by`, idempotency principals, rate-limit buckets — never `preferred_username` | `test:shared/security/SubjectAttributionTest` (six tests) |
| NFR-AUD-5 | A change with no principal on the thread SHALL be recorded as such: `audit_log.actor` null, `created_by`/`updated_by` the `"system"` sentinel — which records "there was no principal", distinct from a null meaning "unset" | `test:audit/internal/AuditRecordingTest.systemTriggeredChangeHasNoActor`; `test:shared/security/SubjectAttributionTest.auditColumnsRecordTheSubjectNotTheUsername`, `.updatedByAlsoRecordsTheSubject` |
| NFR-AUD-6 | There SHALL be **no `deleted_by` column**: `@SQLDelete` is raw SQL and cannot see the security context, so the column would be reliably populated only on the paths that bypass it; the actor lives in the `audit_log` row the deleting service writes | `main:shared/persistence/SoftDeletableEntity.java:28-32` (design rationale); no test |
| NFR-AUD-7 | Audit reads SHALL be tenant-scoped on the org endpoint and tier-gated on the platform endpoint | `test:audit/internal/AuditApiTest.orgScopedViewReturnsOnlyThatOrgWhenPermitted`, `.platformViewIsAdminOnly` |
| NFR-AUD-8 | Inside an impersonation session `audit_log.actor` SHALL name the **accountable operator**, with the worn identity in `on_behalf_of` and the session in `impersonation_id` — and this SHALL be the only attribution on the request that differs from the effective subject | `test:identity/internal/ImpersonationReachTest.anAuditRowFromInsideASessionNamesTheOperatorAndTheIdentityTheyWore`, `.aWebhookCreatedInsideASessionNamesTheOperatorAndTheSession` |
| NFR-AUD-9 | `impersonation_session` SHALL be end-only, never soft-deletable and never removed: the operator a delete would serve is the one whose reach the row records | `V18__impersonation_session.sql:3-9` (design rationale); `main:identity/internal/ImpersonationSession.java:17-27`; no test — the absence of a delete path is what enforces it |

**Coverage gap:** webhook subscription mutations, file operations and notification mark-read produce
no audit row. Every organization, settings and identity mutation does.

### 5.4 Performance and caching (NFR-PERF)

All values are the shipped defaults, each traceable to a property or constant.

| ID | Requirement | Limit | Source |
|---|---|---|---|
| NFR-PERF-1 | Listing SHALL be O(page) at any depth, with no `COUNT` and no offset | keyset only | ADR 0002 |
| NFR-PERF-2 | A page SHALL be capped | `page[size]` ≤ 100, default 20 | `CursorPageRequest` |
| NFR-PERF-3 | Hot config reads SHALL be served from a two-level cache | L1 Caffeine `PT60S` / 10 000 entries; L2 Valkey `PT10M` | `app.cache.*` |
| NFR-PERF-4 | An L1 miss SHALL be backfilled from L2 without a database read | `TwoLevelCache.get` | `test:shared/cache/ValkeyCacheIntegrationTest.l2ServesWhenL1IsGone` |
| NFR-PERF-5 | Cached values SHALL survive an L2 round trip with their types intact | polymorphic typing restricted to an allow-list; root-level JDK immutable collections shallow-copied | `test:shared/cache/ValkeyCacheIntegrationTest.nonScalarValuesKeepTheirTypeAcrossAnL2OnlyRead` |
| NFR-PERF-6 | A write SHALL invalidate peers' L1 caches | pub/sub topic `smsone:cache:invalidations`; nodes skip their own broadcasts | `test:shared/cache/ValkeyCacheIntegrationTest.foreignInvalidationBroadcastEvictsL1` |
| NFR-PERF-7 | Remote calls made inside a user-facing request SHALL have mandatory timeouts | Keycloak Admin 5 s connect / 15 s read; Valkey 2 s command and connect; rate-limit backend 250 ms; SMTP 5 s connect/read/write; webhook send 5 s; notification HTTP 10 s connect + 5 s exchange | `application.yaml`; `KeycloakAdminProperties`; `HttpChannels` |
| NFR-PERF-8 | The connection pool SHALL be at least as large as notification delivery concurrency, so fan-out status writes cannot starve request traffic | Hikari max 16, connection timeout 30 000 ms; delivery concurrency 16 | `application.yaml` |
| NFR-PERF-9 | Analytics SHALL be resource-bounded per DuckDB instance and in aggregate | threads 2, memory 512 MB, ephemeral concurrency permits 2 | `app.analytics.*` |
| NFR-PERF-10 | Queue claims SHALL not block one another | `FOR UPDATE SKIP LOCKED` with partial claim indexes on both queues | `V9`, `V15` |
| NFR-PERF-11 | Purges SHALL be batched so a backlog never becomes one long-lived transaction | soft delete: 500 rows/batch, 100 batches/table/run, each batch on its own connection | `SoftDeleteProperties`; `SoftDeletePurgeJob` |
| NFR-PERF-12 | Rate-limit tiers SHALL be enforced per window | write 60/min (TENANT), read 600/min (TENANT), default 300/min (PRINCIPAL). A custom tier may also use scope `IP`; those are the three values `RateLimitScope` defines (FR-HTTP-26) | `app.rate-limit.tiers`, `app.rate-limit.default-tier` |
| NFR-PERF-13 | A rate-limit capacity change SHALL be understood to take effect for an **active** key only after the bucket TTL | 10 minutes | `DistributedRateLimiter.BUCKET_TTL` |

**Known unindexed sorts.** `setting`, `feature_flag`, `app_user` and `membership` are listed on
`(createdAt desc, id desc)` with no supporting composite index; only `in_app_notification`,
`audit_log` and `webhook_delivery` have one. `idx_app_user_email` indexes the raw column while the
only email query is case-insensitive, so it likely serves nothing the code runs.

### 5.5 Reliability and degradation (NFR-REL)

| ID | Requirement | Behaviour | Verification |
|---|---|---|---|
| NFR-REL-1 | Event delivery SHALL be at-least-once | Registry rows persist per (event, listener); incomplete publications republish on restart | `test:scheduler/EventPurgeJobIntegrationTest.purgesCompletedPublications` |
| NFR-REL-2 | Consumers with side effects SHALL be effectively-once | `EventInbox` dedupe in the listener's own transaction | `test:shared/events/EventInboxIntegrationTest` |
| NFR-REL-3 | Notification and webhook delivery SHALL survive process death | Durable queue rows; a crashed claimant's row is reclaimed after `stale-lock` (notification PT5M, webhook PT2M) | `test:notification/internal/NotificationDeliveryQueueTest.staleClaimantsUpdatesAreFencedOutAfterReclaim` |
| NFR-REL-4 | A reclaimed row SHALL NOT be corrupted by its previous claimant | Every terminal update fenced on `status = 'PROCESSING' and attempts = ?`; a stale update matches zero rows and logs | `test:notification/internal/NotificationDeliveryQueueTest.staleClaimantsUpdatesAreFencedOutAfterReclaim` |
| NFR-REL-5 | Retries SHALL use capped exponential backoff and terminate in a dead letter | `base << min(attempts-1, 16)`, capped; notification PT10S→PT10M, webhook PT10S→PT1H; 5 attempts each | `test:notification/NotificationDeliveryTest.transient5xxIsRetriedWithBackoffUntilDeadLettered`; `test:webhooks/internal/WebhookDeliveryTest.transientFailureIsRetriedThenDeadLettered` |
| NFR-REL-6 | Permanent failures SHALL NOT burn retries | `status < 500 && != 408 && != 429` ⇒ permanent | `test:notification/NotificationDeliveryTest.permanent4xxIsDeadLetteredWithoutBurningRetries` |
| NFR-REL-7 | A poison row SHALL NOT block a queue | Unknown channel dead-lettered inside `claim` | `test:notification/internal/NotificationDeliveryQueueTest.unknownChannelRowIsDeadLetteredInsteadOfPoisoningTheBatch` |
| NFR-REL-8 | A hung channel SHALL NOT stall the poller | Permit acquired inside the task, not on the poller; batch wait bounded by `stale-lock`; `RejectedExecutionException` leaves the row for reclaim | `test:notification/NotificationDeliveryTest.fansOutHundredsConcurrentlyWithoutDuplicates` |
| NFR-REL-9 | Storage failures SHALL open a circuit breaker, while a business not-found SHALL NOT trip it and presigning SHALL stay outside it | window 10, min 5 calls, 50 % threshold, PT10S open | `test:files/ResilienceSmokeTest.circuitBreakerOpensUnderFaultInjection` |
| NFR-REL-10 | A cache backend outage SHALL degrade to L1-only rather than fail requests, and SHALL NOT broadcast after a failed L2 evict (which would let peers refill from a stale L2) | `TwoLevelCache` | `test:shared/cache/ValkeyOutageIntegrationTest.survivesValkeyDyingMidFlight` |
| NFR-REL-11 | A rate-limit backend outage SHALL fail **open** by default, short-circuit for 2 s after an error rather than storming reconnects, and connect lazily so startup never depends on Valkey | `DistributedRateLimiter` | none |
| NFR-REL-12 | Scheduled jobs SHALL run at most once cluster-wide, on DB-server time | ShedLock, `usingDbTime()`, defaults `lockAtMostFor PT10M` / `lockAtLeastFor PT30S` | `test:scheduler/SchedulerLockIntegrationTest.onlyOneOfTwoInstancesAcquiresTheLock` |
| NFR-REL-13 | A purge failure on one table SHALL NOT starve the tables behind it | Per-table try/catch, continue, rethrow the first failure at the end | `test:scheduler/internal/SoftDeletePurgeJobIntegrationTest.aLiveMembershipPinningAnAgedRoleDoesNotStarveTheTablesBehindIt` |
| NFR-REL-14 | Concurrent writes to an aggregate SHALL be detected optimistically, and last-owner removal SHALL be serialised pessimistically | `@Version` on every `BaseEntity`; `PESSIMISTIC_WRITE` on the owner count, `PESSIMISTIC_READ`/`WRITE` on role reference and delete | `test:organization/internal/OrgRbacApiTest.removingTheLastOwnerIsBlocked`; `test:shared/persistence/SoftDeleteTest.aConcurrentUpdateCannotResurrectADeletedRow` |
| NFR-REL-15 | Workers SHALL stop before the DataSource closes and drain in-flight work | `SmartLifecycle` phase `Integer.MAX_VALUE - 100`, 20 s grace | none |
| NFR-REL-16 | A mart refresh failure SHALL leave the previous mart intact | Staging table + atomic swap | `test:analytics/AnalyticsIntegrationTest.failedRefreshLeavesThePreviousMartIntact` |

**Unbounded growth — two real gaps.** `event_inbox` has **no purge job**, and
`webhook_delivery`'s `purgeDeliveredBefore` has **no caller** and no retention property. Both tables
grow without bound. `event_publication`, `idempotency_key`, `notification_delivery` and the seven
soft-deletable tables all have working retention.

### 5.6 Observability (NFR-OBS)

| ID | Requirement | Implementation |
|---|---|---|
| NFR-OBS-1 | Every request SHALL carry a public correlation id, present in the response body (`meta.requestId`), the response header (`X-Request-Id`) and every log line for that request (MDC key `requestId`) | `RequestIdFilter`; `logback-spring.xml` |
| NFR-OBS-2 | Internal trace identifiers (`traceId`, `spanId`) SHALL stay internal — in logs, never on the wire | `RequestIdFilter` javadoc; log pattern |
| NFR-OBS-3 | Logs SHALL be human-readable in `local`/`test` and structured JSON (Logstash encoder, `service` field) everywhere else | `logback-spring.xml`, two `<springProfile>` blocks, root `INFO` |
| NFR-OBS-4 | A 500 SHALL be traceable from the client's request id to exactly one logged stack trace | `GlobalExceptionHandler:119-126` — the only place a stack trace is logged |
| NFR-OBS-5 | Liveness and readiness SHALL be exposed as Kubernetes-shaped probes without authentication and without leaking component detail | `management.endpoint.health.probes.enabled: true`, `show-details: never` |
| NFR-OBS-6 | Lock state SHALL be inspectable at runtime without a database session | `GET /api/v1/scheduler/locks` |

**Limitation.** There is **no custom instrumentation** — no `MeterRegistry`, `@Timed`, `@Counted` or
`Observation` anywhere in `src/main`. Everything is framework-produced (`spring-boot-starter-opentelemetry`,
`spring-modulith-observability`, `micrometer-java21`). No dead-letter, throttle or 429 counter exists;
the retired backlog listed them as deferred, and they remain open. **No OTLP configuration exists in
`application.yaml`** — Boot's defaults apply.

### 5.7 Portability (NFR-PORT)

| ID | Requirement | Implementation |
|---|---|---|
| NFR-PORT-1 | Every infrastructure coordinate SHALL be externalised as `${ENV:default}`, so moving from Compose to Kubernetes is configuration, not code | `application.yaml` throughout; `docker/.env.example` uses the same names |
| NFR-PORT-2 | Object storage SHALL work unchanged against SeaweedFS, AWS S3, R2 or B2 by swapping endpoint and credentials | AWS SDK v2, `endpointOverride`, path-style access |
| NFR-PORT-3 | Cache and rate limiting SHALL use only Redis-protocol features available in Valkey (BSD), avoiding AGPL/SSPL exposure for a redistributable template | ADR 0001 |
| NFR-PORT-4 | The analytics engine SHALL be swappable (ClickHouse/Trino) without touching callers | `AnalyticsEngine` port, ADR 0006 |
| NFR-PORT-5 | All Spring configuration SHALL be YAML; the only `.properties` file SHALL be `gradle.properties`, which configures the build daemon and never the application | ADR 0001 |
| NFR-PORT-6 | Schema evolution SHALL be Flyway-only and forward-only, with Hibernate restricted to `validate` | `application.yaml:27-29` |

**Gap.** There is no `Dockerfile` and no `deploy/` or `k8s/` directory. Portability is designed for
but not yet exercised.

### 5.8 Maintainability and module boundaries (NFR-MNT)

| ID | Requirement | Enforcement |
|---|---|---|
| NFR-MNT-1 | No module SHALL reach into another module's `internal` package | `test:ModularityTests.verifiesModularStructure` → `ApplicationModules.verify()` |
| NFR-MNT-2 | `shared` SHALL be the only `Type.OPEN` module; business modules SHALL never be OPEN | `shared/package-info.java`; verified by NFR-MNT-1 |
| NFR-MNT-3 | `shared` SHALL NOT compile-depend on any business module; it SHALL declare a port and default-deny/no-op instead | `OrgAuthorization`, `AuditLog`; verified by NFR-MNT-1 |
| NFR-MNT-4 | Field injection SHALL NOT be used | `test:ArchitectureTests.noFieldInjection` |
| NFR-MNT-5 | Generic exceptions SHALL NOT be thrown | `test:ArchitectureTests.noGenericExceptions` |
| NFR-MNT-6 | Standard streams SHALL NOT be used | `test:ArchitectureTests.noStandardStreams` |
| NFR-MNT-7 | Every soft-deletable entity SHALL declare its **own** `@SQLRestriction("deleted_at is null")` and a `@SQLDelete` whose SQL exactly matches the template for that entity's own `@Table` name, including `version = version + 1` | `test:ArchitectureTests.softDeletableEntitiesDeclareTheirOwnHibernateAnnotations` — reconstructs the expected string per entity and fails on any drift |
| NFR-MNT-8 | The soft-delete purge order SHALL cover every `SoftDeletableEntity` in the JPA metamodel, without duplicates, and every such entity SHALL name its table explicitly | `test:scheduler/internal/SoftDeletePurgeJobIntegrationTest.purgeOrderCoversEverySoftDeletableEntity` |
| NFR-MNT-9 | Module documentation SHALL be generated from the code, not written by hand | `test:DocumentationGenerationTest.generatesModuleDiagramsAndCanvases` (`docs-export` tag) → `docs/modulith/` |
| NFR-MNT-10 | The published OpenAPI spec SHALL be generated and kept in the repository | `test:shared/web/OpenApiExportTest.exportsOpenApiSpec` (`openapi-export` tag) → `docs/openapi/` |
| NFR-MNT-11 | Cursor parameter names SHALL be published correctly in the spec and SHALL match what the server honours | `test:shared/web/CursorPaginationContractTest` (four tests) |
| NFR-MNT-12 | Lombok SHALL NOT be used; records and constructor injection instead | ADR 0001, review |

Review-enforced rules with no automated gate (`AGENTS.md` §1): Testcontainers-only, cursor-only
pagination, no cross-module foreign keys, no JIT provisioning, disjoint authorization axes,
subject-not-username for durable keys, `audit_log` append-only, no Lombok.

### 5.9 Testability (NFR-TST)

| ID | Requirement | Implementation |
|---|---|---|
| NFR-TST-1 | Every infra-touching test SHALL run against a **real container** — no H2, no embedded substitutes, no mocked repositories | ADR 0003; `test:testsupport/AbstractIntegrationTest` |
| NFR-TST-2 | Postgres SHALL be a JVM-wide singleton container so context caching is not defeated | `AbstractIntegrationTest` static block, `max_connections=400` |
| NFR-TST-3 | Tests SHALL NOT depend on a running OTLP collector, Valkey, or the rate limiter unless they start their own | `application-test.yaml` disables OTLP export, cache L2, rate limiting, the provisioning gate, the organization dev bootstrap and both delivery workers. Each **except the provisioning gate** is re-enabled by the IT that owns it; `app.provisioning.gate-enabled: false` (`:29`) is the only occurrence of the key anywhere in `src/test`, so `ProvisioningGateFilter` — a `@ConditionalOnProperty` bean — is **never instantiated in any test run**, and the filter itself is never exercised (§7.5, FR-IDN-1..3) |
| NFR-TST-4 | A retention default SHALL be tested at its **shipped** value, not a convenient one | `SoftDeletePurgeJobIntegrationTest` runs against the real `P30D` window |
| NFR-TST-5 | Business code SHALL be able to take a `Clock` bean so time-dependent behaviour is controllable | `shared/config/ClockConfig`; consumed by `JpaAuditingConfig`, `AuditLogImpl`, `EventInbox`, `IdempotencyStore`, `SoftDeletePurgeJob`, `UserAccessService` |

**Testability limits.** `deleted_at` is written by two different clocks, neither of them the `Clock`
bean: **Postgres `now()`** on every `@SQLDelete` path (all seven entities), and a raw `Instant.now()`
in `RoleService.delete` (`main:organization/internal/RoleService.java:123`, `role.softDelete(Instant.now())`)
— the one deletion that bypasses `@SQLDelete` on purpose, see FR-DLC-11. `MemberService.remove` is
**not** a third writer: it calls `memberships.delete(membership)`
(`main:organization/internal/MemberService.java:138`), which goes through `Membership`'s `@SQLDelete`
and Postgres `now()`; the `Instant.now()` on the following line is the `MemberRemoved` event's
`occurredAt`, not a deletion stamp. Either way a test that fixes the `Clock` cannot control
`deleted_at`, so tests that need aged rows write it in raw SQL instead.

---

## 6. Data requirements

### 6.1 Where the data model is documented

**`docs/DATA_MODEL.md` is the data-model reference.** It carries the per-column tables for all 17
tables, the entity hierarchy, the soft-delete mechanism, the migration history and the retention
picture, written against this same working tree. This section does not restate it; it gives the
inventory and the three structural facts a reader of *this* document needs, and defers the columns.

The other authoritative sources, in order:

1. **`src/main/resources/db/migration/V1…V17.sql`** — the schema itself. Each migration carries a
   header explaining *why*, not just *what*; `V9`, `V12`, `V14`, `V16` and `V17` in particular record
   decisions that cannot be recovered from the resulting DDL.
2. **The ten `@Entity` classes** — nine mapped through `BaseEntity`: `Setting`, `FeatureFlag`, `User`,
   `Organization`, `Role`, `Membership`, `WebhookSubscription` (all `SoftDeletableEntity`),
   `AuditEntry` and `InAppNotification` (both plain `BaseEntity`) — plus `WebhookDelivery`, a bare
   `@Entity` read model with no `@Version` whose `payload` column is deliberately unmapped.
   (`BaseEntity` itself is not an `@Entity`; it matches a naive grep only through `@EntityListeners`.)
3. **`docs/modulith/module-*.adoc`** — generated per-module canvases.

**The 17 tables.** Ownership is per §2.2; the migration column is where the table is created.

| Table | Owner | Created | What it holds |
|---|---|---|---|
| `flyway_schema_history` | Flyway (framework) | — (created by Flyway itself, in no migration) | Applied-migration ledger |
| `event_publication` | Spring Modulith (framework) | `V2` | The outbox: one row per *(event, registered listener)*; `completion_date` null = incomplete |
| `shedlock` | ShedLock (framework) | `V4` | One row per named job lock; `TIMESTAMP` without zone on purpose (`usingDbTime()`) |
| `setting` | `settings` | `V3` (+`V17`) | Platform key/value configuration; unique `setting_key` over live rows |
| `feature_flag` | `settings` | `V6` (+`V17`) | Platform flags; unique `flag_key` over live rows |
| `idempotency_key` | `shared` | `V5` | Claim-first replay store, PK `(principal, idem_key)` where principal is the **subject** |
| `event_inbox` | `shared` | `V7` | Consumer-side dedupe, PK `(listener_id, message_id)` |
| `in_app_notification` | `notification` | `V8` | Delivered in-app messages, keyed by recipient **subject**; never soft-deletable |
| `notification_delivery` | `notification` | `V9` (+`V12`) | The durable fan-out queue; `V9` also **drops** the `V8` `notification_log` |
| `app_user` | `identity` | `V10` (+`V17`) | Local projection of a Keycloak account + provisioning lifecycle; unique `subject` over live rows |
| `organization` | `organization` | `V11` (+`V17`) | Local projection of a Keycloak Organization; unique `kc_org_id` and `alias` over live rows |
| `org_role` | `organization` | `V11` (+`V16`, `V17`) | Per-org role; unique `(org_id, code)` over live rows |
| `role_permission` | `organization` | `V11` | `@ElementCollection` of `org_role`, PK `(role_id, permission)`; **not** soft-deletable — it follows its role's lifecycle |
| `membership` | `organization` | `V11` (+`V17`) | Subject ↔ role inside one org; unique `(org_id, user_subject)` over live rows |
| `audit_log` | `audit` | `V13` (+`V14`) | Append-only trail; `V14` drops `detail` for `from_state`/`to_state`; never soft-deletable |
| `webhook_subscription` | `webhooks` | `V15` (+`V17`) | Per-org outbound endpoint, event set, status and signing secret |
| `webhook_delivery` | `webhooks` | `V15` | The signed-delivery queue and log; a log, not an aggregate — never soft-deletable |

`V1` is an intentionally empty baseline. The three framework tables and `role_permission`,
`audit_log`, `in_app_notification` and `webhook_delivery` are the seven tables `V17` deliberately
leaves out of soft delete; the other seven all gained `deleted_at` (FR-DLC-1, FR-DLC-10).

Three structural facts worth stating here:

- **Every column value is supplied by the application.** The only DB `DEFAULT` in the entire schema
  is `notification_delivery.attempts int not null default 0`. Primary keys are generated in Java
  (`@UuidGenerator`, or `UUID.randomUUID()` in the JDBC queues) — no `gen_random_uuid()` anywhere.
- **Three foreign keys exist, all intra-module** (§2.2). Two are now largely dormant under soft
  delete: `webhook_delivery.subscription_id`'s cascade can only fire during the hard-delete purge,
  never during a user-facing delete, and `role_permission`'s cascade is precisely why role deletion
  bypasses `@SQLDelete` (FR-DLC-11).
- **Two vocabularies for one concept.** `role_permission.permission` stores the `Permission` **enum
  constant name** (`ORG_READ`) because of `@Enumerated(EnumType.STRING)`, while the REST API and the
  audit trail use the wire code (`org:read`). Nothing in the column name signals which is stored.

### 6.2 Retention

| Data | Window | Mechanism | Configurable via |
|---|---|---|---|
| Soft-deleted rows in the seven aggregate tables | **P30D** | `SoftDeletePurgeJob`, nightly 04:00 by default, batched | `app.persistence.soft-delete.{retention, purge-enabled, batch-size, max-batches}`; schedule `app.scheduler.soft-delete-purge-cron` |
| Completed event publications | **P7D** | `EventPublicationPurgeJob`, nightly 03:00 by default | `app.scheduler.event-retention` (**not present in `application.yaml`** — `@Value` default only); schedule `app.scheduler.event-purge-cron` |
| Idempotency keys | **P1D** | `IdempotencyPurgeJob`, nightly 03:30 by default | `app.idempotency.retention` (**not in `application.yaml`**); schedule `app.scheduler.idempotency-purge-cron` |
| Terminal (`SENT`/`FAILED`) notification deliveries | **P7D** | `NotificationRetentionJob` — nightly, ShedLock-guarded, batched | `app.notification.delivery.retention` |
| Terminal (`DELIVERED`/`FAILED`) webhook deliveries | **P30D** | `WebhookRetentionJob` — nightly, ShedLock-guarded, batched | `app.webhooks.retention` |
| `event_inbox` dedup rows | **P14D** (must stay ≥ event retention) | `EventInboxPurgeJob` — nightly, ShedLock-guarded, batched | `app.scheduler.event-inbox-retention` |
| `audit_log` | **indefinite** | none — append-only by design | — |
| `in_app_notification` | **indefinite** | none | — |
| `webhook_delivery` | **indefinite** — a gap | `purgeDeliveredBefore` exists but has **no caller**; no `app.webhooks.retention` property exists | — |
| `event_inbox` | **indefinite** — a gap | no purge path anywhere | — |
| Analytics marts | overwritten on each report run | staging-table swap | — |
| Parquet snapshots | indefinite, on disk under `app.analytics.snapshot-dir` | none | — |

All three nightly times are **defaults, not constants** — each `@Scheduled` cron is a
`${app.scheduler.*-cron:…}` placeholder with no entry in `application.yaml` (§3.11, §10).

`SoftDeleteProperties` fails at **startup** on a negative retention (which would purge rows deleted
in the future — that is, everything) or a batch size or max-batches below 1. A **zero** retention is
deliberately allowed: it is how tests and one-off erasures ask for "now".

### 6.3 Personal data

| Personal data | Stored where | Notes |
|---|---|---|
| Email address | `app_user.email` (`varchar(320)`); also transiently in the invite call to Keycloak; `audit_log.to_state` on `identity.user_provisioned` (`"email=<address>"`) | The system of record for the identity is Keycloak; this is a projection |
| Keycloak subject | `app_user.subject`, `membership.user_subject`, `audit_log.actor`, `in_app_notification.recipient`, `idempotency_key.principal`, `created_by`/`updated_by`, rate-limit keys, file object keys (`u/<subject>/…`) | Pseudonymous — an opaque UUID, not a name |
| Display name | **not stored.** First/last name are passed to Keycloak at provisioning and never persisted locally | `CreateOrganizationRequest.ownerFirstName`/`ownerLastName` carry no constraints and no local column |
| Message content | `in_app_notification.subject`/`body`; `notification_delivery.subject`/`body`/`recipient` | Recipient may be an email address or phone number depending on channel |
| Client IP | Rate-limit bucket key only, when neither tenant nor subject resolves; capped at 45 characters; **redacted before logging** | Not persisted to any table |
| Webhook payloads | `webhook_delivery.payload` | Contains subjects and role/org ids; deliberately **not exposed** through the delivery-log API |

### 6.4 What is deliberately not stored

- **Passwords and credentials of any kind.** The application never creates, sets, transmits, logs or
  reads a password. Credential establishment is entirely Keycloak's: `issueTemporaryCredentials`
  triggers `PUT /users/{id}/execute-actions-email` with `["UPDATE_PASSWORD", "VERIFY_EMAIL"]`, and
  the user sets their own password in Keycloak's UI. The action-email is the **only** credential
  mode — a server-generated temporary password was rejected because it had no delivery channel and
  stranded accounts. Neither the application nor the inviting administrator ever sees a credential
  value. There is no password column, no hash, no reset token and no refresh token anywhere in the
  schema.
- **No `deleted_by` column** — see NFR-AUD-6.
- **No display names**, as above.
- **No offsets or totals** for any collection (ADR 0002) — nothing counts rows for presentation.
- **No client-supplied SQL.** Analytics SQL is developer-authored per enum constant; clients select
  a report by code.
- **No webhook payload in the delivery-log API** — `WebhookDelivery.payload` is intentionally
  unmapped so the log shows outcome, not the body.

### 6.5 Erasure

Erasure is a **two-stage** process and there is currently **no HTTP route into either stage** for a
user account.

1. **Stage 1 — soft delete.** `repository.delete(entity)` (or `Role.softDelete`) stamps `deleted_at`.
   The row becomes invisible to every JPA read path, its unique key is freed for reuse, and it is
   restorable via `SoftDeleteRecovery` for the retention window.
2. **Stage 2 — purge.** `SoftDeletePurgeJob` hard-deletes rows past `app.persistence.soft-delete.retention`
   (P30D), children before parents, batched, nightly. This is irreversible. `app.persistence.soft-delete.purge-enabled`
   is a freeze switch that needs no redeploy.

**What this means for a subject-erasure request today:**

- `app_user` and `organization` are fully wired for soft delete and appear in `PURGE_ORDER`, but **no
  application code deletes either** — grep finds no `.delete(`/`.deleteById(` on `UserRepository` or
  `OrganizationRepository`. Reaching stage 1 for an account requires direct SQL.
- Erasing an account does **not** erase its footprint. `audit_log` is append-only by design and
  retains `actor` (a subject) indefinitely; `in_app_notification` retains `recipient` with no
  retention; `webhook_delivery.payload` retains subjects with no working retention; `event_inbox`
  retains message ids with no purge. Files under `u/<subject>/` are only removed by an explicit
  delete call.
- A fork with an erasure obligation must add: an account-disable and account-delete path, retention
  for `in_app_notification` and `webhook_delivery`, an `event_inbox` purge, and a policy decision on
  the audit trail (whose append-only property is a deliberate, `AGENTS.md`-level rule).

---

## 7. Verification

### 7.1 Strategy

`./gradlew build` runs every gate. There is no separate integration-test source set and no tag-based
exclusion of the slow tests: **54 test classes executing 274 tests** (266 `@Test` + 1
`@ParameterizedTest` declarations, the parameterized one and several table-driven loops contributing
more than one execution each) **and 4 `@ArchTest` rules**, of which 45 classes extend
`AbstractIntegrationTest` and therefore need Docker. (`src/test` holds 58 Java files;
`testsupport/AbstractIntegrationTest`, `shared/web/EnvelopeTestController`,
`identity/internal/ImpersonationFixtures` and `organization/internal/OrgRbacFixtures` carry no tests and
are support classes.) Two tasks are tagged and run selectively — `exportModulithDocs` (`docs-export`)
and `exportOpenApi` (`openapi-export`).

### 7.2 How each requirement class is verified

| Requirement class | Method | Representative tests |
|---|---|---|
| **Module boundaries** (NFR-MNT-1..3) | `ApplicationModules.verify()` — no container, no Spring context | `ModularityTests.verifiesModularStructure` |
| **Code-level invariants** (NFR-MNT-4..8) | ArchUnit over `ug.co.smsone` with tests excluded | `ArchitectureTests.noFieldInjection`, `.noGenericExceptions`, `.noStandardStreams`, `.softDeletableEntitiesDeclareTheirOwnHibernateAnnotations` |
| **HTTP contract** (FR-HTTP-1..16) | MockMvc against the real context, using a test-classpath controller (`/test/echo`, `/test/signup`, `/test/boom`, `/test/missing`) that is excluded from the published spec | `EnvelopeContractTest`, `ProblemDetailContractTest`, `CursorPaginationContractTest` |
| **Authentication and tiers** (FR-PLT, NFR-SEC-2..4) | Both synthetic (`jwt().authorities(...)`, exactly one authority per case) and **real Keycloak 26.7.0 containers importing the committed realm** | `PlatformRoleHierarchyTest`, `KeycloakIntegrationTest`, `KeycloakJwtAuthenticationConverterTest` |
| **Org authorization** (FR-ORG, NFR-TEN) | Full HTTP RBAC matrix plus direct authority resolution against real Postgres | `OrgRbacApiTest` (23 tests), `OrgRbacAuthorityTest` (14 tests) |
| **Keycloak wire contracts** | Live container ITs that have already caught two real gateway bugs (alias search, non-idempotent member add) | `KeycloakOrgAdminIntegrationTest`, `KeycloakProvisioningIntegrationTest` (with a real Mailpit on a shared Docker network) |
| **Persistence and soft delete** (FR-DLC) | Real Postgres; raw SQL where `@SQLRestriction` would hide the rows under test | `SoftDeleteTest` (12 tests), `RoleSoftDeleteTest`, `SoftDeletePurgeJobIntegrationTest` (5 tests), `FlywayBaselineTest` |
| **Durable delivery** (FR-NOT, FR-WHK, NFR-REL-3..8) | Real Postgres queues driven deterministically (`workerAutoStart: false`, `drainOnce()` invoked directly), with a real Mailpit and an in-test HTTP receiver | `NotificationDeliveryTest`, `NotificationDeliveryQueueTest`, `WebhookDeliveryTest`, `WebhookApiTest` |
| **Events** (FR-EVT) | Modulith `@ApplicationModuleTest(ALL_DEPENDENCIES)` slices with `Scenario`, plus registry assertions | `SettingsModuleTest`, `WebhookEventTest`, `EventInboxIntegrationTest` |
| **Cache** (NFR-PERF-3..6, NFR-REL-10) | Real Valkey container, including one test that **kills the container mid-flight** | `ValkeyCacheIntegrationTest`, `ValkeyOutageIntegrationTest` |
| **Rate limiting** (FR-HTTP-25..29) | Real Valkey; the test raises `backend-timeout` to PT5S and records that production keeps the 250 ms fail-fast default | `RateLimitIntegrationTest` |
| **Idempotency** (FR-HTTP-17..23) | Real Postgres, including a same-username/different-subject case | `IdempotencyIntegrationTest`, `SubjectAttributionTest` |
| **Storage** (FR-FIL) | Real SeaweedFS 4.40, plus fault injection for the breaker | `FileStorageIntegrationTest`, `FileApiTest`, `ResilienceSmokeTest` |
| **Analytics** (FR-ANA) | Embedded DuckDB over real Postgres data, with a pinned per-test database file to avoid single-writer contention | `AnalyticsIntegrationTest`, `AnalyticsApiTest` |
| **Scheduling** (FR-SCH) | Real Postgres ShedLock rows; the purge test defeats `lockAtLeastFor` by expiring the lock row rather than deleting it | `SchedulerLockIntegrationTest`, `EventPurgeJobIntegrationTest`, `SchedulerApiTest` |
| **Generated artefacts** (NFR-MNT-9..11) | Export tasks that assert on their own output | `DocumentationGenerationTest`, `OpenApiExportTest` |

### 7.3 Test environment

| Image | Used by |
|---|---|
| `postgres:18.4-alpine` | `AbstractIntegrationTest` — one JVM-wide singleton shared by **48** classes (the 45 that extend `AbstractIntegrationTest`, plus the three `@ApplicationModuleTest` slices — `SettingsModuleTest`, `WebhookEventTest`, `NotificationDeliveryTest` — that reuse its static container without extending it), `max_connections=400` |
| `quay.io/keycloak/keycloak:26.7.0` | `KeycloakIntegrationTest`, `KeycloakOrgAdminIntegrationTest`, `KeycloakProvisioningIntegrationTest` — plain `GenericContainer`, `start-dev --import-realm` with the committed realm |
| `axllent/mailpit:v1.30.2` | `KeycloakProvisioningIntegrationTest` (network alias `mailpit`, matching the realm's SMTP host), `NotificationDeliveryTest` |
| `chrislusf/seaweedfs:4.40` | `FileStorageIntegrationTest` |
| `valkey/valkey:8-alpine` | `ValkeyCacheIntegrationTest`, `ValkeyOutageIntegrationTest` (owns its own, because it kills it), `RateLimitIntegrationTest` |

Six classes need no container: `ArchitectureTests`, `ModularityTests`, `DocumentationGenerationTest`,
`ProvisioningPropertiesTest`, `SafeOutboundUrlTest`, `KeycloakJwtAuthenticationConverterTest`.

### 7.4 Continuous integration

`.github/workflows/ci.yml` is the only workflow: checkout → Temurin 21 → `setup-gradle` →
`./gradlew build`, on push to `main`, on pull request, and on manual dispatch.

**Absent:** no image pre-pull, no test-report or artifact upload, no matrix, no image build or
publish, no dependency/vulnerability/licence scanning, no Dependabot, no CodeQL, no release or deploy
workflow, no README badge. the retired backlog recorded that runs were blocked by a GitHub
account-level billing lock, so **"CI green on `main`" is an open item, not a delivered gate**.

### 7.5 Requirements with no automated verification

Listed so they are not mistaken for verified. Each is implemented; none is covered by a test.

| Area | Uncovered |
|---|---|
| Security | FR-PLT-8 (audience **rejection** — the control most worth a negative test), FR-IDN-4's gate branch, NFR-SEC-11 |
| Request pipeline | FR-HTTP-24 (lease takeover), FR-HTTP-26's `IP` scope, FR-HTTP-27 (`X-Forwarded-For` handling), FR-HTTP-30 / NFR-REL-11 (fail-open) |
| Identity | **FR-IDN-2 and FR-IDN-3 at the HTTP level.** `application-test.yaml:29` disables `app.provisioning.gate-enabled` suite-wide; the one class that re-enables it is `ImpersonationProvisioningGateTest` (`@TestPropertySource`), which covers FR-IDN-1's `ACCOUNT_NOT_PROVISIONED` and FR-IDN-10's activation but nothing else. **No test issues a request to `/api/v1/me`, and `ACCOUNT_DISABLED` appears nowhere in `src/test`** — the `/me` leniency rule and the disabled-account code are both unverified over HTTP. FR-IDN-11 (`/api/v1/me` has no dedicated test), FR-IDN-14 (identity dev bootstrap) |
| Organizations | FR-ORG-10's alias pattern, FR-ORG-11's projection path (`OrgProjectionWriter.projectWithOwner` has no test caller), FR-ORG-18's role-code pattern and non-empty permission set, FR-ORG-20 (reserved-code create), FR-ORG-27's failed-unlink branch, FR-ORG-31 (organization dev bootstrap — disabled suite-wide by `application-test.yaml:30-33`), FR-ORG-5's `SUSPENDED` membership case (unreachable, so untestable without raw SQL) |
| Files | FR-FIL-9 (bad presign operation), FR-FIL-13 (bucket bootstrap) |
| Notifications | FR-NOT-1, FR-NOT-10, FR-NOT-12, FR-NOT-13, FR-NOT-14, FR-NOT-16, FR-NOT-19..23 — the in-app REST surface has **no API test at all** |
| Webhooks | FR-WHK-17 (subscription status — no test creates, reads or asserts a `DISABLED` subscription, nor the re-enable-by-omission behaviour of `PUT`) |
| Audit | FR-AUD-4 (truncation), FR-AUD-6's `occurred_at` window versus `created_at` ordering |
| Scheduling | FR-SCH-3 (idempotency purge) |
| Data lifecycle | FR-DLC-9 (startup validation) |
| Reliability | NFR-REL-15 (graceful shutdown) |

---

## 8. Traceability matrix

Grouped by area. `main:` = `src/main/java/ug/co/smsone/`, `test:` = `src/test/java/ug/co/smsone/`.
Per-requirement detail is in §3 and §5; this table is the area-level rollup plus the artefacts that
do not appear there. **✗** marks a requirement with no automated verification (§7.5).

| IDs | Implementing files | Verifying tests |
|---|---|---|
| FR-HTTP-1..9 | `main:shared/web/{EnvelopeResponseBodyAdvice,ApiResponse,ApiError,ApiMeta,ApiMetaFactory,ApiLinks,ApiSource,ResourceObject}.java`, `main:shared/error/{GlobalExceptionHandler,ErrorCode,ApiException,ValidationException,NotFoundException,ConflictException,ForbiddenException,UnauthorizedException}.java`, `main:shared/web/EnvelopeErrorWriter.java` | `test:shared/web/EnvelopeContractTest`, `test:shared/error/ProblemDetailContractTest` |
| FR-HTTP-3..4 | `main:shared/web/RequestIdFilter.java` | `test:shared/web/EnvelopeContractTest.echoesValidInboundRequestId`, `.replacesMalformedInboundRequestId` |
| FR-HTTP-10..11, 31..32 | `main:shared/security/{SecurityConfig,ApiAuthenticationEntryPoint,ApiAccessDeniedHandler}.java` | `test:shared/security/SecurityContractTest`, `test:shared/security/PlatformRoleHierarchyTest.forbiddenIsRenderedAsTheEnvelope` |
| FR-HTTP-12..16 | `main:shared/web/{CursorPageRequest,CursorPageRequestArgumentResolver,Cursors,WindowedResult,PageMeta,WebMvcConfig,OpenApiConfig}.java` | `test:shared/web/CursorPaginationContractTest`, `test:settings/SettingsApiIntegrationTest.cursorPaginationWalksTheCollection`, `.invalidCursorYields422` |
| FR-HTTP-17..23 | `main:shared/idempotency/{IdempotencyFilter,IdempotencyStore,CachedBodyRequestWrapper}.java`, `V5__idempotency_key.sql` | `test:shared/idempotency/IdempotencyIntegrationTest` (7 tests), `test:shared/security/SubjectAttributionTest` |
| FR-HTTP-24 ✗, 27 ✗, 30 ✗ | `main:shared/idempotency/IdempotencyStore.java`, `main:shared/ratelimit/{RateLimitKeyResolver,DistributedRateLimiter}.java` | — |
| FR-HTTP-25..29 | `main:shared/ratelimit/{RateLimitFilter,RateLimitProperties,RateLimitKeyResolver,DistributedRateLimiter,RateLimitConfig,RateLimitScope,RateLimitVerdict}.java` | `test:shared/ratelimit/RateLimitIntegrationTest` (5 tests) |
| FR-IDN-1, 2 ✗, 3 ✗ (HTTP level), 10 | `main:identity/internal/{ProvisioningGateFilter,UserAccessService,User}.java` | `test:identity/internal/IdentityProvisioningTest.gateDeniesUnprovisionedThenActivatesInvitedOnFirstHit`, `.disabledUserIsDeniedByBothAuthorizeAndPeek` (`UserAccessService` decisions); `test:identity/internal/ImpersonationProvisioningGateTest` (4 tests) is the only place the filter runs over HTTP — it covers FR-IDN-1 and FR-IDN-10, not FR-IDN-2 or the `ACCOUNT_DISABLED` code (§7.5) |
| FR-IDN-4 ✗ | `main:identity/internal/{UserAccessService,UserRepository}.java` | (read side only) `test:analytics/internal/AnalyticsApiTest.softDeletedUsersAreExcludedFromTheReport` |
| FR-IDN-5..9 | `main:identity/internal/{UserProvisioningService,KeycloakUserAdminGateway,ProvisioningProperties}.java`, `main:shared/keycloak/{KeycloakAdminClientConfig,KeycloakServiceAccountTokens,KeycloakAdminProperties}.java`, `V10__identity_user.sql` | `test:identity/internal/IdentityProvisioningTest` (6 tests), `test:identity/internal/KeycloakProvisioningIntegrationTest` (3 tests), `test:identity/internal/ProvisioningPropertiesTest` (3 `@Test` + 1 `@ParameterizedTest`) |
| FR-IDN-11 ✗, 14 ✗ | `main:identity/internal/{MeController,PlatformAdminBootstrap,IdentityDevBootstrapProperties}.java` | — |
| FR-IDN-12..13 | `main:identity/internal/{UserAdminController,UserDirectoryService}.java` | `test:shared/security/PlatformRoleHierarchyTest`, `test:notification/NotificationDeliveryTest.flagToggleEnqueuesThenWorkerDeliversEmailAndInApp` |
| FR-IDN-15..19 | `main:identity/internal/{IdentityReconciliationJob,IdentityReconciliationProperties,KeycloakUserAdminGateway}.java`; `application.yaml:102-118` | `test:identity/internal/IdentityReconciliationJobTest` (7 tests) |
| FR-ORG-1..2, 6 | `main:organization/Permission.java`, `main:organization/internal/PermissionCatalogController.java` | `test:organization/internal/OrgRbacApiTest.unknownPermissionCodeOnRoleCreateIs422`, `.permissionCatalogIsReadableByAnyAuthenticatedUser`, `test:organization/internal/OrgRbacAuthorityTest.aRoleCodeGrantsNothingByItself` |
| FR-ORG-3..4, 29 | `main:organization/internal/{RoleSeeder,SystemRoleCatalogReconciler,Role}.java`, **`V16__org_role_owner_only.sql`** | `test:organization/internal/OrgRbacAuthorityTest.aFreshOrganizationHasExactlyOneRole`, `.seederReconcilesADriftedSystemRoleBackToTheCatalog`, `.seederLeavesFormerSystemRolesAloneAsCustomRoles`, `test:organization/internal/OrgRbacApiTest.aRoleNamedAdminIsJustAnotherCustomRole` |
| FR-ORG-5, 7..9 | `main:organization/internal/{PermissionResolver,OrgAuthorizationImpl,OrgPermissionCacheEvictor}.java`, `main:shared/security/{ApiPermissionEvaluator,OrgAuthorization}.java` | `test:organization/internal/OrgRbacAuthorityTest` (9 tests), `test:organization/internal/OrgRbacApiTest.crossOrgAccessIsDeniedBeforeAnyDbHit`, `.tokenWithNoActiveOrgIsDenied` |
| FR-ORG-10..14 | `main:organization/internal/{OrganizationController,OrganizationService,OrgProjectionWriter,KeycloakOrgAdminGateway,Organization,OrganizationStatus}.java`, `V11__organization_rbac.sql` | `test:organization/internal/OrgRbacApiTest.duplicateAliasCreateIsConflictNotAdoption`, `.createRefusesToAdoptAnExistingKeycloakOrg`, `.suspendIsPlatformAdminOnlyAndCutsMemberAccess`, `.memberCannotUpdateOrgButOwnerCan`, `test:organization/internal/KeycloakOrgAdminIntegrationTest` |
| FR-ORG-15..17, 25..28 | `main:organization/internal/{MemberController,MemberService,Membership,MembershipRepository,MembershipStatus}.java` | `test:organization/internal/OrgRbacApiTest.ownerInviteProvisionsAcrossModulesAndCreatesMembership`, `.removingTheLastOwnerIsBlocked`, `.ownerCanPromoteAMemberToOwner`, `.aRemovedMemberLeavesTheListingAndReleasesTheirRole`, `test:organization/internal/OrgRbacAuthorityTest.aSoftDeletedMembershipResolvesToZeroPermissions`, `.aRemovedMemberCanBeInvitedBackIntoTheSameOrganization` (both via the private helper `removeMemberAsProductionDoes`) |
| FR-ORG-18, 20 ✗, 21..24 | `main:organization/internal/{RoleController,RoleService,Role,RoleRepository}.java` | `test:organization/internal/OrgRbacApiTest.memberCannotCreateRoleButOwnerCan`, `.systemRoleUpdateIsForbidden`, `.anOrgRoleCodeCannotBorrowThePlatformVocabulary`, `.aDeletedRoleCodeCanBeMintedAgain` |
| FR-ORG-19 | `main:organization/internal/PermissionEscalationGuard.java` | `test:organization/internal/OrgRbacApiTest.aCallerCannotGrantAPermissionItDoesNotHold`, `.aNonOwnerCannotSelfPromoteToOwner`, `.aNonOwnerCannotInviteAnOwner` |
| FR-ORG-30, 31 ✗ | `main:organization/internal/{RoleService,MemberService}.java`, `main:webhooks/internal/WebhookSubscriptionService.java`; `main:organization/internal/{OrgDevBootstrap,OrgDevBootstrapProperties,OrganizationService}.java`, `application.yaml:129-138` | `test:organization/internal/OrgRbacAuthorityTest.permissionsAreScopedToTheOwningOrganization`; the dev bootstrap is disabled suite-wide (`test/resources/application-test.yaml:30-33`) and has **no test** |
| FR-PLT-1..7, 14 | `main:shared/security/{PlatformRole,SecurityConfig,CurrentUserProvider,CurrentUser,KeycloakJwtAuthenticationConverter,ApiPermissionEvaluator}.java`, `docker/keycloak/realm-smsone.json` | `test:shared/security/PlatformRoleHierarchyTest` (6 tests), `test:shared/security/KeycloakJwtAuthenticationConverterTest` (3 tests), `test:shared/security/SubjectAttributionTest` |
| FR-PLT-8 ✗ | `application.yaml:12-17`, realm client scope `smsone-api-audience` | **none — negative case unproven** |
| FR-PLT-9..13 | see FR-AUD-6..7, FR-SCH-5, FR-ANA-2, FR-SET-2/8 | `test:shared/security/KeycloakIntegrationTest` (4 tests) |
| FR-IMP-1, 13..21, 23 | `main:identity/internal/{ImpersonationController,ImpersonationService,ImpersonationSession,ImpersonationSessionRepository,ImpersonationMode,ImpersonationProperties}.java`, **`V18__impersonation_session.sql`** | `test:identity/internal/ImpersonationApiTest` (16 tests); `test:identity/internal/KeycloakProvisioningIntegrationTest.realmRolesReportsRolesHeldThroughACompositeNotJustDirectMappings` (FR-IMP-14's composite half, against real Keycloak) |
| FR-IMP-2, 4..11 | `main:shared/security/{ImpersonationFilter,ImpersonationLookup,ImpersonatedPrincipal,ImpersonatedAuthenticationToken,CurrentUser,CurrentUserProvider}.java`, `main:identity/internal/ImpersonationLookupImpl.java` | `test:shared/security/ImpersonationFilterTest` (6 tests), `test:identity/internal/ImpersonationReachTest` (13 tests) |
| FR-IMP-3, 12 | `main:identity/internal/{ProvisioningGateFilter,UserAccessService}.java` | `test:identity/internal/ImpersonationProvisioningGateTest` (4 tests) — the only context in the suite with `app.provisioning.gate-enabled=true`, which is also the only HTTP-level coverage the gate has at all (cf. FR-IDN-1..3) |
| FR-IMP-22, 24 | `main:audit/internal/{AuditLogImpl,AuditEntry,AuditController}.java`, **`V19__audit_log_impersonation.sql`** | `test:identity/internal/ImpersonationReachTest.anAuditRowFromInsideASessionNamesTheOperatorAndTheIdentityTheyWore`, `.aWebhookCreatedInsideASessionNamesTheOperatorAndTheSession` |
| FR-IMP-25 | `@Value("${app.impersonation.enabled:true}")` on `main:identity/internal/ImpersonationController.java:55` (enforced per handler by `requireEnabled()`) and `main:shared/security/ImpersonationFilter.java:72`; `application.yaml:119-128` | `test:identity/internal/ImpersonationDisabledTest` (3 tests) |
| FR-LOC-1..6 | `main:localization/{Messages,TranslationChanged}.java`, `main:localization/internal/{Translation,TranslationRepository,TranslationBundles,MessagesImpl,TranslationService,TranslationController,LocalizationProperties}.java`, **`V21__localization.sql`** | `test:localization/internal/MessageResolutionTest` (3 tests), `test:localization/internal/LocalizationApiTest` (4 tests) |
| FR-SRCH-1..6 | `main:search/{SearchIndex,SearchDoc}.java`, `main:search/internal/{SearchIndexStore,SearchIndexImpl,SearchEventListeners,SearchQueryService,SearchController}.java`, **`V22__search.sql`**, `main:shared/web/Cursors.java` (the `d:` tag) | `test:search/internal/SearchApiTest` (5 tests), `test:search/internal/SearchPerformanceTest` (measured p95), `test:shared/web/CursorsEscapingTest` |
| FR-SET-1..11 | `main:settings/internal/{SettingController,SettingService,Setting,SettingRepository,FeatureFlagController,FeatureFlagService,FeatureFlag,FeatureFlagRepository}.java`, `main:settings/{SettingChanged,FeatureFlagChanged}.java`, `V3`, `V6` | `test:settings/SettingsApiIntegrationTest` (6), `test:settings/FeatureFlagIntegrationTest` (4), `test:settings/SettingsModuleTest` (2) |
| FR-FIL-1..8, 9 ✗, 10..12, 13 ✗ | `main:files/internal/{FileController,S3StorageProvider,S3ClientConfig,StorageProperties,BucketBootstrap}.java`, `main:files/{FileStorageProvider,FileStorageException,FileNotFoundException}.java` | `test:files/internal/FileApiTest` (10), `test:files/FileStorageIntegrationTest` (3), `test:files/ResilienceSmokeTest` |
| FR-NOT-2..9, 11, 15, 17..18 | `main:notification/internal/{NotificationService,NotificationDeliveryQueue,NotificationDeliveryWorker,ChannelRegistry,ChannelRateLimiter,EmailChannelSender,InAppChannelSender,SlackChannelSender,WebhookChannelSender,SmsChannelSender,HttpChannels,NotificationProperties,FeatureFlagChangeNotifier}.java`, `V8`, `V9`, `V12` | `test:notification/NotificationDeliveryTest` (5), `test:notification/internal/NotificationDeliveryQueueTest` (3), `test:shared/ratelimit/RateLimitIntegrationTest.egressChannelLimitDefersExcessDeliveries`, `test:shared/http/SafeOutboundUrlTest` |
| FR-NOT-1 ✗, 10 ✗, 12..14 ✗, 16 ✗, 19..23 ✗ | `main:notification/internal/{ChannelRegistry,NotificationDeliveryWorker,InAppNotificationService,InAppNotificationRepository,NotificationController,InAppNotification}.java` | **none** |
| FR-WHK-1..16, 17 ✗ | `main:webhooks/internal/{WebhookController,WebhookSubscriptionService,WebhookSubscription,WebhookSubscriptionRepository,WebhookEventType,WebhookEventListener,WebhookDispatcher,WebhookPayload,WebhookSigner,WebhookSender,WebhookDeliveryQueue,WebhookDeliveryWorker,WebhookDelivery,WebhookDeliveryException,WebhookProperties,SubscriptionStatus}.java`, `V15__webhooks.sql` | `test:webhooks/internal/WebhookApiTest` (6), `test:webhooks/internal/WebhookDeliveryTest` (4), `test:webhooks/internal/WebhookEventTest`; subscription status has **no test** |
| FR-AUD-1..3, 5..10 | `main:shared/audit/{AuditLog,AuditLogConfiguration}.java`, `main:audit/internal/{AuditLogImpl,AuditEntry,AuditEntryRepository,AuditController}.java`, `V13`, `V14` | `test:audit/internal/AuditRecordingTest` (3), `test:audit/internal/AuditApiTest` (6) |
| FR-AUD-4 ✗ | `main:audit/internal/AuditEntry.java:60-65` | — |
| FR-ANA-1..10 | `main:analytics/internal/{AnalyticsReport,AnalyticsReportService,AnalyticsReportController,DuckDbAnalyticsEngine,AnalyticsProperties}.java`, `main:analytics/{AnalyticsEngine,AnalyticsException}.java` | `test:analytics/AnalyticsIntegrationTest` (7), `test:analytics/internal/AnalyticsApiTest` (5) |
| FR-SCH-1..2, 4..5 | `main:scheduler/internal/{SchedulingConfig,EventPublicationPurgeJob,SoftDeletePurgeJob,SchedulerController}.java`, `V4__shedlock.sql` | `test:scheduler/SchedulerLockIntegrationTest` (2), `test:scheduler/EventPurgeJobIntegrationTest`, `test:scheduler/internal/SchedulerApiTest` (2), `test:scheduler/internal/SoftDeletePurgeJobIntegrationTest` (5) |
| FR-SCH-3 ✗ | `main:scheduler/internal/IdempotencyPurgeJob.java` | — |
| FR-EVT-1..7 | `main:shared/persistence/AggregateRoot.java`, `main:shared/events/EventInbox.java`, `main:shared/config/AsyncConfig.java`, the ten event records, `main:organization/internal/OrgPermissionCacheEvictor.java`, `main:notification/internal/FeatureFlagChangeNotifier.java`, `main:webhooks/internal/{WebhookEventListener,WebhookDispatcher}.java`, `V2`, `V7` | `test:shared/events/EventInboxIntegrationTest` (2), `test:settings/SettingsModuleTest.upsertPublishesSettingChangedThroughTheRegistry`, `test:webhooks/internal/WebhookEventTest`, `test:organization/internal/RoleSoftDeleteTest` |
| FR-DLC-1..5, 10..13 | `main:shared/persistence/{SoftDeletableEntity,AggregateRoot,BaseEntity,SoftDeleteRecovery,SoftDeleteProperties,JpaAuditingConfig}.java`, the eight soft-deletable entities, **`V17__soft_delete.sql`** + `V21` | `test:shared/persistence/SoftDeleteTest` (12), `test:organization/internal/RoleSoftDeleteTest` (2), `test:ArchitectureTests.softDeletableEntitiesDeclareTheirOwnHibernateAnnotations`, `test:shared/persistence/FlywayBaselineTest` |
| FR-DLC-6..8 | `main:scheduler/internal/SoftDeletePurgeJob.java` | `test:scheduler/internal/SoftDeletePurgeJobIntegrationTest` (5) |
| FR-DLC-9 ✗ | `main:shared/persistence/SoftDeleteProperties.java` | — |
| NFR-SEC-1..17 | see §5.1 | see §5.1; NFR-SEC-2 (audience) and NFR-SEC-11 unverified. NFR-SEC-15..17 are the impersonation invariants — `test:identity/internal/ImpersonationReachTest`, `test:shared/security/ImpersonationFilterTest` |
| NFR-TEN-1..8 | see §5.2 | `test:organization/internal/{OrgRbacApiTest,OrgRbacAuthorityTest}`, `test:webhooks/internal/WebhookApiTest`, `test:files/internal/FileApiTest`, `test:shared/ratelimit/RateLimitIntegrationTest`, `test:shared/idempotency/IdempotencyIntegrationTest` |
| NFR-AUD-1..5, 7..8 | `main:audit/internal/{AuditLogImpl,AuditEntry}.java`, `main:shared/persistence/JpaAuditingConfig.java`, `main:shared/security/{CurrentUser,CurrentUserProvider}.java` | `test:shared/security/SubjectAttributionTest` (6), `test:audit/internal/AuditRecordingTest` (3), `test:identity/internal/ImpersonationReachTest.anAuditRowFromInsideASessionNamesTheOperatorAndTheIdentityTheyWore` |
| NFR-AUD-9 ✗ | `V18__impersonation_session.sql`, `main:identity/internal/ImpersonationSession.java` | — (enforced by the absence of a delete path, not by a test) |
| NFR-AUD-6 ✗ | `main:shared/persistence/SoftDeletableEntity.java:28-32` (design decision) | — |
| NFR-PERF-3..6, NFR-REL-10 | `main:shared/cache/{CacheConfig,CacheProperties,TwoLevelCacheManager,TwoLevelCache,CacheInvalidationBroadcaster}.java` | `test:shared/cache/ValkeyCacheIntegrationTest` (4), `test:shared/cache/ValkeyOutageIntegrationTest` |
| NFR-MNT-1..3 | all ten `package-info.java` files | `test:ModularityTests.verifiesModularStructure` |
| NFR-MNT-4..8 | ArchUnit-enforced source rules; `main:scheduler/internal/SoftDeletePurgeJob.java` (`PURGE_ORDER`) | `test:ArchitectureTests` (4 rules), `test:scheduler/internal/SoftDeletePurgeJobIntegrationTest.purgeOrderCoversEverySoftDeletableEntity` |
| NFR-MNT-9..11 | `main:shared/web/OpenApiConfig.java`; Modulith `Documenter` | `test:DocumentationGenerationTest`, `test:shared/web/OpenApiExportTest`, `test:shared/web/CursorPaginationContractTest` |
| NFR-TST-1..5 | `test:testsupport/AbstractIntegrationTest`, `src/test/resources/application-test.yaml`, `main:shared/config/ClockConfig.java` | the suite itself; ADR 0003 |

---

## 9. Open items

Everything in this section is **PLANNED or a known gap**.

### 9.1 Audited impersonation — moved to §3.14

**No longer an open item.** Slice C shipped on 2026-07-31; impersonation is now a first-class
requirement set, **[§3.14 (FR-IMP-1..25)](#314-audited-impersonation-fr-imp)**, with the schema in
[DATA_MODEL.md](DATA_MODEL.md) §8 and the invariants in [../AGENTS.md](../AGENTS.md) §5.5. This
heading is kept so links from earlier revisions still land somewhere useful; it will be removed once
they have aged out.

Two consequences are worth repeating here because they are the ones a reader of §9 is most likely to
be looking for:

1. The impersonated principal holds **no** authority at all. That single fact is why org permissions
   still resolve from the database for the target (so tenant endpoints work) and why every
   `hasRole('platform-*')` check fails inside a session (so `/api/v1/admin/**`, including the endpoint
   that mints sessions, is unreachable from within one).
2. `audit_log.actor` is the accountable human — the operator — while every other durable attribution
   on the request (`created_by`, `updated_by`, the rate-limit bucket, the idempotency key) records the
   effective subject, the target. That inversion is deliberate and lives in exactly one place,
   `main:audit/internal/AuditLogImpl.attribution()`.

### 9.2 Functional gaps in the built system

| Gap | Detail | Suggested resolution |
|---|---|---|
| **No delete route for settings or feature flags** | `SettingService.delete` / `FeatureFlagService.delete` exist and are the only cache-correct removal path; neither controller declares `@DeleteMapping` | Add the two `DELETE` endpoints under `platform-admin`, or document the omission as intentional |
| **No user-disable or user-delete path** | `User.disable()` has no caller outside reconciliation; nothing deletes `app_user` or `organization`, though both are fully soft-delete-wired and in `PURGE_ORDER` | Needed before any erasure obligation can be met (§6.5) |
| **Soft-delete restore is unreachable over HTTP** | `SoftDeleteRecovery` is injected by no controller | Decide whether restore is an operator API or deliberately Java-only |
| **Webhook secrets stored in plaintext** | `webhook_subscription.secret varchar(200)`; returned into the claim result set by design | Encrypt at rest, or accept and document |
| **No CORS** | Nothing anywhere in `src/main` | Required before any cross-origin browser client |
| **No negative audience test** | The control exists and is configured; nothing proves rejection | Mint an `smsone-admin` client-credentials token in `KeycloakIntegrationTest` and assert 401 |
| **Unrestricted settings / feature-flag reads** | The four GET endpoints carry no authority check | Confirm intent and document, or gate on `platform-support` |
| **Three ungated permissions** | `org:delete`, `org:settings:read`, `org:settings:update` are grantable but referenced by no `@PreAuthorize` | Build the endpoints or remove the codes |
| **No custom metrics** | No counter on dead-letters, throttles or 429s | Backlog retired — still open |
| **No spec error schemas** | The OpenAPI export documents only success responses and the unwrapped payload; the `/files` operations leak a bogus `user` query parameter | See §4.8 |
| **CI never confirmed green** | Single `./gradlew build` job; runs were blocked by an account-level billing lock | Backlog retired — still open |
| **No container image or Kubernetes manifests** | No `Dockerfile`, no `deploy/` | Backlog retired — still open |
| **SMS is a logging stub, on by default** | `SmsChannelSender` with `matchIfMissing = true` | Register a real sender and set `app.notification.sms.stub=false` |
| **The SSRF guard is switchable off** | `app.webhooks.allow-private-hosts` and `app.notification.webhook-allow-private-hosts` each disable address validation for their module entirely (§5.1); both are `true` in the test profile | Assert both are false in production configuration, and pair the guard with an egress network policy |
| **Webhook subscription status is invisible in the contract** | `status` (`ACTIVE`\|`DISABLED`) is returned on every read and set by `PUT`, but there is no enable/disable endpoint and a `PUT` omitting `status` silently re-enables a disabled subscription (FR-WHK-17) | Add an explicit enable/disable route, or make `PUT` preserve an unspecified status |
| **`OrgProjectionWriter.projectWithOwner` has no test** | The only production path that writes an org + seeded `OWNER` + first membership; every test assembles that state by hand (FR-ORG-11) | Add an IT that drives `OrganizationService.create` with the Keycloak gateway mocked |

### 9.3 Documentation drift to reconcile

None open. The last three items — the `FeatureFlagChangeNotifier` javadoc (documented the message id
without its `@occurredAt` component and inverted the re-toggle semantics), the `docs/EVENTS.md`
publisher table (role soft-delete as a `RolePermissionsChanged` publisher, the webhook wire codes,
the four zero-consumer events), and `docs/LOCAL_ACCESS.md`'s machine-specific ports — were fixed in
the 2026-08-01 docs restructure.

Everything else previously listed here was reconciled in the 2026-08-01 cleanup pass: `audit/package-info.java`
now describes synchronous recording through the port; `AGENTS.md` §4.2's `@SQLDelete` template carries the
version increment and §7 states the real purge-error rule (isolate per independent table, rethrow at the end)
and the sanctioned `@Scheduled`-outside-`scheduler` case; `docs/ARCHITECTURE.md` and `README.md` show all ten
modules; `docs/CHECKLIST.md`, `docs/NEXT_TASKS.md`, `docs/COMPLETED_MODULES.md`, `docker/.env.example` and
`gradle/libs.versions.toml` were corrected.

---

## 10. Configuration reference

Every `app.*` prefix bound by a `@ConfigurationProperties` record, plus the `@Value` keys and the
`@ConditionalOnProperty` switches, with the defaults the code actually applies. **"Code default" means
the value a record's compact constructor or `@DefaultValue` supplies when the key is absent; it is not
always the same as the `application.yaml` value, and several keys have no `application.yaml` entry at
all.** `${ENV:default}` names in parentheses are the environment variables `application.yaml` reads.

There are **thirteen** `@ConfigurationProperties` records.

**`app.keycloak-admin`** — `main:shared/keycloak/KeycloakAdminProperties.java`

| Key | Default | Note |
|---|---|---|
| `base-url` | `http://localhost:8081` (`KEYCLOAK_URL`) | No code default |
| `realm` | `smsone` (`KEYCLOAK_REALM`) | Code default `smsone` |
| `client-id` | `smsone-admin` (`KEYCLOAK_ADMIN_CLIENT_ID`) | — |
| `client-secret` | `smsone-admin-secret` (`KEYCLOAK_ADMIN_SECRET`) | Dev value; **always override** |
| `connect-timeout` | `PT5S` (code only) | Not in `application.yaml` |
| `read-timeout` | `PT15S` (code only) | Not in `application.yaml` |

**`app.provisioning`** — `main:identity/internal/ProvisioningProperties.java`

| Key | Default | Note |
|---|---|---|
| `gate-enabled` | `true` (`PROVISIONING_GATE_ENABLED`) | Read by `@ConditionalOnProperty(matchIfMissing = true)` on `ProvisioningGateFilter:30` — **absent means on**; the whole no-JIT gate is this one flag. `false` suite-wide in tests (NFR-TST-3) |
| `invite-lifespan` | `PT12H` (`PROVISIONING_INVITE_LIFESPAN`) | Code default `PT12H`; zero/negative coerced |
| `redirect-uri` | empty (`PROVISIONING_REDIRECT_URI`) | — |
| `app-client-id` | `smsone-web` (`PROVISIONING_APP_CLIENT`) | Code default `smsone-web` |
| `default-realm-role` | `USER` (`PROVISIONING_DEFAULT_REALM_ROLE`) | Blank grants none; a platform role **fails startup** (FR-IDN-7) |

**`app.identity.dev-bootstrap`** — `main:identity/internal/IdentityDevBootstrapProperties.java` (FR-IDN-14)

| Key | Default | Note |
|---|---|---|
| `enabled` | `false` (`IDENTITY_DEV_BOOTSTRAP_ENABLED`) | `@ConditionalOnProperty` with **no** `matchIfMissing` — absent means off |
| `email` | `ayesigapo@gmail.com` (`IDENTITY_DEV_BOOTSTRAP_EMAIL`) | Projects an **existing** Keycloak account only |

**`app.identity.reconciliation`** — `main:identity/internal/IdentityReconciliationProperties.java` (FR-IDN-15)

| Key | Default | Note |
|---|---|---|
| `enabled` | `true` (`IDENTITY_RECONCILIATION_ENABLED`) | Read by `@ConditionalOnProperty(matchIfMissing = true)` on `IdentityReconciliationJob:45` — **absent means on**. Deliberately **not** a record component, the same convention `ProvisioningProperties` documents |
| `action` | `REPORT` (`IDENTITY_RECONCILIATION_ACTION`) | `REPORT` \| `DISABLE`. Ships as `REPORT`: this is the only scheduled job that can revoke access, so a fork watches what it would have done first. Null coerced to `REPORT` |
| `grace-period` | `PT1H` (`IDENTITY_RECONCILIATION_GRACE`) | Keeps the job off rows young enough to still be mid-provisioning. Null/negative coerced |
| `max-orphan-ratio` | `0.10` (`IDENTITY_RECONCILIATION_MAX_ORPHAN_RATIO`) | Misconfiguration circuit breaker — above it the run changes **nothing** and logs an error. Outside `(0, 1]` coerced to `0.10` |
| `batch-size` | `500` (`IDENTITY_RECONCILIATION_BATCH_SIZE`) | Keycloak lookups per run. Below 1 coerced |

**`app.impersonation`** — `main:identity/internal/ImpersonationProperties.java` (FR-IMP-*)

| Key | Default | Note |
|---|---|---|
| `enabled` | `true` (`IMPERSONATION_ENABLED`) | Read by `@Value`, not by this record — see the `@Value` table below. `false` makes the routes and the header **refuse (403)**, not vanish |
| `default-ttl` | `PT15M` (`IMPERSONATION_DEFAULT_TTL`) | Minutes, not hours: a session covers one investigation |
| `max-ttl` | `PT30M` (`IMPERSONATION_MAX_TTL`) | Hard cap. An over-cap request is **rejected (422)**, never clamped — a silently shortened session surfaces as an unexplained denial mid-investigation. A default above the cap **fails startup** |

**`app.organization.dev-bootstrap`** — `main:organization/internal/OrgDevBootstrapProperties.java` (FR-ORG-31)

Six keys — `enabled`, `alias`, `name`, `owner-email`, `owner-first-name`, `owner-last-name`. Defaults
and the Keycloak state this runner creates are tabulated under FR-ORG-31 in §3.3.

**`app.rate-limit`** — `main:shared/ratelimit/RateLimitProperties.java`

| Key | Default | Note |
|---|---|---|
| `enabled` | `true` (`RATE_LIMIT_ENABLED`) | `@ConditionalOnProperty(matchIfMissing = true)` on `RateLimitFilter:29`, `RateLimitKeyResolver:21` and `RateLimitConfig:24` — all three beans vanish when false. `false` in the test profile. Deliberately **not** a record component: the flag is honoured by the conditionals, never read from the record |
| `key-prefix` | `rl` | Code default `rl`; the `<prefix>:<tier>:<type>` part retained when a key is logged (NFR-SEC-11) |
| `tenant-claim` | `tenant` (`RATE_LIMIT_TENANT_CLAIM`) | Flat-claim fallback for non-org IdPs; code default `tenant` |
| `backend-timeout` | `PT0.25S` (`RATE_LIMIT_BACKEND_TIMEOUT`) | The fail-fast that makes fail-open real |
| `trust-forwarded-for` | `false` (`RATE_LIMIT_TRUST_XFF`) | Only true behind a trusted proxy (FR-HTTP-27) |
| `tiers[]` | `write` 60/PT1M TENANT, `read` 600/PT1M TENANT | `{id, path-pattern, methods, scope, capacity, refill-period, fail-closed}`; `scope` ∈ `IP`\|`PRINCIPAL`\|`TENANT` (FR-HTTP-26) |
| `default-tier` | `default` 300/PT1M PRINCIPAL | Applied when no tier matches |

**`app.webhooks`** — `main:webhooks/internal/WebhookProperties.java`

| Key | Default | Note |
|---|---|---|
| `worker-auto-start` | `true` (`WEBHOOKS_WORKER_AUTOSTART`) | Code default TRUE — only an explicit `false` (tests) disables the poller |
| `batch-size` | `50` (`WEBHOOKS_BATCH_SIZE`) | — |
| `poll-interval` | `PT5S` (`WEBHOOKS_POLL_INTERVAL`) | — |
| `stale-lock` | `PT2M` (`WEBHOOKS_STALE_LOCK`) | Reclaim window for a crashed claimant |
| `max-attempts` | `5` (`WEBHOOKS_MAX_ATTEMPTS`) | — |
| `retry-base-backoff` / `retry-max-backoff` | `PT10S` / `PT1H` | — |
| `timeout-seconds` | `5` (`WEBHOOKS_TIMEOUT`) | — |
| `allow-private-hosts` | `false` (`WEBHOOKS_ALLOW_PRIVATE_HOSTS`) | **Disables the SSRF guard for this module entirely** (FR-WHK-3, NFR-SEC-9) |

**`app.notification`** — `main:notification/internal/NotificationProperties.java`

| Key | Default | Note |
|---|---|---|
| `from` | `no-reply@smsone.co.ug` (`NOTIFICATION_FROM`) | — |
| `admins[].email` | `ayesigapo@gmail.com` (`NOTIFICATION_ADMIN_EMAIL`) | The in-app subject is resolved from this email at dispatch (FR-NOT-4) |
| `slack-webhook-url` | empty (`NOTIFICATION_SLACK_WEBHOOK`) | — |
| `webhook-timeout-seconds` | `5` (`NOTIFICATION_WEBHOOK_TIMEOUT`) | — |
| `webhook-allow-private-hosts` | `false` — **code default only, no `application.yaml` entry** | **Disables the SSRF guard for the Slack and webhook channels** (FR-NOT-17, NFR-SEC-9); `true` in the test profile |
| `delivery.batch-size` / `.concurrency` / `.max-attempts` | `200` / `16` / `5` | Concurrency is why the Hikari pool is ≥ 16 (NFR-PERF-8) |
| `delivery.poll-interval` / `.retry-base-backoff` / `.retry-max-backoff` | `PT1S` / `PT10S` / `PT10M` | — |
| `delivery.stale-lock` / `.retention` | `PT5M` / `P7D` | A zero stale-lock is coerced — it would reclaim every in-flight row. Retention is enforced by `NotificationRetentionJob` (the old in-loop `purge-interval` knob is gone) |
| `delivery.max-drain-batches` | `25` | Bounds one drain pass |
| `delivery.throttle-delay` / `.throttle-max-age` | `PT1S` / `PT1H` | FR-NOT-11, FR-NOT-12 |
| `delivery.worker-auto-start` | `true` | Code default TRUE; `false` in tests |
| `delivery.rate.<CHANNEL>.{capacity, period}` | unset = unlimited | Cluster-wide egress limit per channel; commented example at `application.yaml:193-196` |
| `app.notification.sms.stub` | `true` **by `matchIfMissing`** | Registers the logging-stub SMS sender (§3.7); not bound by any record |

**`app.localization`** — `main:localization/internal/LocalizationProperties.java`

| Key | Default | Note |
|---|---|---|
| `default-locale` | `en` (`LOCALIZATION_DEFAULT_LOCALE`) | The fallback chain's floor, lowercased; an unparseable tag fails at startup |

**`app.analytics`** — `main:analytics/internal/AnalyticsProperties.java`

| Key | Default | Note |
|---|---|---|
| `database-path` | `data/analytics.duckdb` (`ANALYTICS_DB_PATH`) | `@DefaultValue`; the durable DuckDB file |
| `snapshot-dir` | `data/snapshots` (`ANALYTICS_SNAPSHOT_DIR`) | Parquet export root; escapes are rejected (FR-ANA-9) |
| `threads` | `2` (`ANALYTICS_THREADS`) | GLOBAL per DuckDB instance |
| `memory-limit` | `512MB` (`ANALYTICS_MEMORY_LIMIT`) | GLOBAL per DuckDB instance |
| `max-ephemeral-concurrency` | `2` — **code default only, no `application.yaml` entry** | Permit count bounding total ephemeral footprint (FR-ANA-8) |

**`app.persistence.soft-delete`** — `main:shared/persistence/SoftDeleteProperties.java`

| Key | Default | Note |
|---|---|---|
| `purge-enabled` | `true` (`SOFT_DELETE_PURGE_ENABLED`) | Freeze switch needing no redeploy |
| `retention` | `P30D` (`SOFT_DELETE_RETENTION`) | Negative **fails startup**; zero is allowed (FR-DLC-9) |
| `batch-size` | `500` (`SOFT_DELETE_PURGE_BATCH_SIZE`) | Below 1 fails startup |
| `max-batches` | `100` (`SOFT_DELETE_PURGE_MAX_BATCHES`) | Below 1 fails startup |

**`app.cache`** — `main:shared/cache/CacheProperties.java`

| Key | Default | Note |
|---|---|---|
| `l1-ttl` / `l1-max-size` | `PT60S` / `10000` (`CACHE_L1_TTL`, `CACHE_L1_MAX_SIZE`) | Caffeine |
| `l2-ttl` | `PT10M` (`CACHE_L2_TTL`) | Valkey |
| `l2-enabled` | `true` (`CACHE_L2_ENABLED`) | Gates three beans in `CacheConfig` (`:56,73,88`) with `matchIfMissing = true`; `false` in the test profile. Deliberately **not** a record component: the flag is honoured by the conditionals, never read from the record |

**`app.storage`** — `main:files/internal/StorageProperties.java`

`endpoint` (`http://localhost:8333`), `region` (`us-east-1`), `access-key` (`smsone`), `secret-key`
(`smsone-secret`), `bucket` (`smsone`), `bootstrap-bucket` (`true`; `false` in the test profile). No
code defaults — every value comes from `application.yaml`'s `${S3_*}` placeholders.

**Keys read by `@Value`, not by any record.** These have no `@ConfigurationProperties` binding and, in
most cases, no `application.yaml` entry — the literal in the annotation is the shipped default.

| Key | Default | Read at |
|---|---|---|
| `app.scheduler.event-purge-cron` | `0 0 3 * * *` | `main:scheduler/internal/EventPublicationPurgeJob.java:30` |
| `app.scheduler.event-retention` | `P7D` | `EventPublicationPurgeJob.java:25` |
| `app.scheduler.idempotency-purge-cron` | `0 30 3 * * *` | `main:scheduler/internal/IdempotencyPurgeJob.java:27` |
| `app.idempotency.retention` | `P1D` | `IdempotencyPurgeJob.java:22` |
| `app.scheduler.soft-delete-purge-cron` | `0 0 4 * * *` | `main:scheduler/internal/SoftDeletePurgeJob.java:98` |
| `app.scheduler.identity-reconciliation-cron` | `0 0 2 * * *` | `main:identity/internal/IdentityReconciliationJob.java:70` |
| `app.impersonation.enabled` | `true` | `main:identity/internal/ImpersonationController.java:55`, `main:shared/security/ImpersonationFilter.java:72` — the one key here that **is** declared in `application.yaml` (`:124`) |
| `app.idempotency.in-progress-lease` | `PT5M` | `main:shared/idempotency/IdempotencyFilter.java:53` (FR-HTTP-24) |
| `app.idempotency.max-body-bytes` | `262144` | `IdempotencyFilter.java:54` (FR-HTTP-19) |
| `OPENAPI_LOCAL_URL` | `http://localhost:8080` | `main:shared/web/OpenApiConfig.java:42` |
| `OPENAPI_STAGING_URL` | `https://staging-api.smsone.co.ug` | `OpenApiConfig.java:43` |
| `OPENAPI_PROD_URL` | `https://api.smsone.co.ug` | `OpenApiConfig.java:44` |

Only `app.impersonation.enabled` appears in `application.yaml`; the other eleven exist solely as the
literal in the annotation. The four cron keys are the ones most likely to be mistaken for constants:
§3.11 and §6.2 quote their default times, not fixed schedules.

**Spring-owned keys this system depends on** are listed with their integrations in §4.7 —
`spring.security.oauth2.resourceserver.jwt.{issuer-uri, audiences}`, `spring.datasource.*`,
`spring.data.redis.*`, `spring.mail.*`, `spring.modulith.events.republish-outstanding-events-on-restart`,
`resilience4j.circuitbreaker.instances.storage.*`, `springdoc.paths-to-exclude` and
`management.*`. **No `management.otlp.*` or other OTLP configuration exists** in `application.yaml`
(NFR-OBS), and no `spring.servlet.multipart.*` (§3.6).

---

*This SRS describes the working tree as read on 2026-07-31. Regenerate `docs/modulith/` and
`docs/openapi/` before relying on either; re-verify any statement here against the cited file before
acting on it.*

