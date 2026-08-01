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
- [x] `shared/persistence` — `BaseEntity`/`AggregateRoot`, UUID keys, auditing
      (soft-delete was listed here but only ever existed as an unused `SoftDeletableEntity` scaffold
      with no column and no entity using it — actually delivered 2026-07-31, below)
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
- [x] `docs/NEXT_TASKS.md` — pickup-ready backlog for the next agent (CI, notification, identity/organization, K8s, rate limiting, event externalization) _(the file was retired 2026-08-01)_

## Notification module ✅ (2026-07-28)

- [x] Pluggable channel SPI (`NotificationChannelSender`) + `Notifications` facade / `NotificationRequest` / `Recipient` (per-recipient channel addressing)
- [x] Channels: Email (SMTP), In-app (persisted + REST), Webhook (HTTP POST), Slack (incoming-webhook POST), SMS (dev stub, `app.notification.sms.stub=false` to replace)
- [x] Per-channel delivery audit (SENT/FAILED); one channel failing never aborts the others —
      the original `notification_log` table was superseded by the durable `notification_delivery`
      queue in **V9**
- [x] `@ApplicationModuleListener` on `FeatureFlagChanged` → notifies admins (email + in-app), idempotent via `EventInbox`; V8 migration
- [x] In-app REST: `GET /api/v1/notifications` (cursor-paginated, own only), `POST /api/v1/notifications/{id}/read`
- [x] Test-infra: capped Hikari pool + Postgres `max_connections=400` (suite outgrew the default 100)
- [x] **Gate:** flag toggle lands an email in real Mailpit + an in-app row; webhook fan-out verified; `verify()` green with 6 modules; full `./gradlew build` green; OpenAPI + Modulith docs regenerated

## Platform roles + hierarchy ✅ (2026-07-31)

Slice A of [archive/PLATFORM_RBAC_IMPERSONATION_PLAN.md](archive/PLATFORM_RBAC_IMPERSONATION_PLAN.md).

- [x] `PlatformRole` vocabulary (`platform-superadmin` → `platform-admin` → `platform-support`);
      `ADMIN` removed from the realm export, `paul` reseated as `platform-superadmin`
- [x] One `RoleHierarchy` bean, published for the web layer and fed to the method-security handler
      via `setAuthorizationManagerFactory` (a custom handler opts out of the auto-configured
      hierarchy; `setRoleHierarchy` is deprecated in Spring Security 7.1)
- [x] `CurrentUserProvider` expands authorities through the hierarchy, so `CurrentUser.hasRole(…)`
      and `@PreAuthorize("hasRole(…)")` cannot answer the same question differently
- [x] 9 `@PreAuthorize` sites retiered (support: `/admin/users`, `/audit`, `/scheduler/locks`,
      `/analytics/reports`; admin: `PUT /settings|/feature-flags`, `POST /orgs`, suspend/reactivate)
- [x] `FileController` cross-namespace access split by blast radius — support reads, admin deletes
      (presign-PUT always mints under the caller's own subject, so there was no write path to tier)
- [x] **Gate:** `PlatformRoleHierarchyTest` — each tier granted exactly ONE authority, asserting
      what it reaches *and* that the ladder does not run downwards
- [x] **Gate:** real Keycloak token — paul holds only `platform-superadmin` yet reaches a
      support-tier endpoint (proves the realm import + mapping + hierarchy end to end)
- [x] **Gate:** full `./gradlew build` green (41 classes, 159 tests); OpenAPI + Modulith docs regenerated

## Org roles → permission-based, OWNER-only ✅ (2026-07-31)

Slice B of [archive/PLATFORM_RBAC_IMPERSONATION_PLAN.md](archive/PLATFORM_RBAC_IMPERSONATION_PLAN.md).

- [x] `RoleSeeder` seeds `OWNER` and only `OWNER`; `Role.OWNER_CODE` is the single constant the
      application names (first-owner bootstrap + last-owner protection)
- [x] `RESERVED_CODES` shrinks to `{OWNER}`; a `PLATFORM*` prefix guard (422) keeps tenant roles from
      reading like platform tiers
- [x] **V16** flips pre-existing `ADMIN`/`MEMBER` to `system_role=false` — flipped, not deleted, since
      `membership.role_id` references them
- [x] Org RBAC tests build their own roles instead of leaning on seeded ones
- [x] **Gate:** a fresh org has exactly one role; owner mints `AUDITOR`, assigns it, holder gets
      exactly those permissions and 403 elsewhere — `OrgRbacAuthorityTest.aFreshOrganizationHasExactlyOneRole`,
      `OrgRbacApiTest.ownerMintsARoleAndItsHolderGetsExactlyThosePermissions`
- [x] **Gate:** a role code grants nothing by itself — `OrgRbacAuthorityTest.aRoleCodeGrantsNothingByItself`,
      `OrgRbacApiTest.aRoleNamedAdminIsJustAnotherCustomRole`, `.aCustomRoleCarryingMemberInviteCanInvite`
- [x] **Gate:** full `./gradlew build` green

## Durable identity is the subject, not the username ✅ (2026-07-31)

- [x] `CurrentUserProvider.currentSubject()` — one answer to "who is the caller, durably"
- [x] `created_by`/`updated_by` record the subject (`JpaAuditingConfig`), not the mutable
      `preferred_username`; `system` sentinel for writes outside a request
- [x] Idempotency keys scope per subject — a recycled username could previously replay the previous
      holder's stored responses (cross-account disclosure, not just bad attribution)
- [x] Rate-limit buckets key by subject — the key type always said `sub` and now means it; a rename
      no longer hands out a fresh quota
- [x] **Gate:** `SubjectAttributionTest` builds authentication through the REAL
      `KeycloakJwtAuthenticationConverter` — the `jwt()` mock defaults the principal name to the
      subject, which is exactly why the whole suite passed while the bug was live

## Soft delete ✅ (2026-07-31)

- [x] All seven `AggregateRoot` tables carry `deleted_at`; `@SQLDelete` + `@SQLRestriction` per entity
      (Hibernate inherits neither from a mapped superclass — a missing one is a silent hard delete or
      a silent leak). `audit_log` and `in_app_notification` stay append-only/disposable by design.
- [x] **V17** — every unique constraint on a soft-deletable table becomes a PARTIAL unique index
      (`where deleted_at is null`), so a deleted key can be reused; without it, deleting the role
      `AUDITOR` would forbid ever creating another
- [x] Partial indexes on `deleted_at is not null` for the retention scan
- [x] Retention purge job + soft-delete behaviour suite
- [x] **Gate:** delete-then-recreate the same key succeeds; a soft-deleted membership resolves to zero
      permissions; deleted rows invisible to every JPA path but present via native SQL

## Audited impersonation ✅ (2026-07-31)

Slice C of [archive/PLATFORM_RBAC_IMPERSONATION_PLAN.md](archive/PLATFORM_RBAC_IMPERSONATION_PLAN.md) — the only
sanctioned path from the platform axis to tenant data, and the only one that leaves a trail.

- [x] `shared.security` gains the seam: `ImpersonationLookup` port + `ImpersonatedPrincipal` +
      `ImpersonatedAuthenticationToken`, implemented by `identity.internal.ImpersonationLookupImpl` —
      the same shape as `OrgAuthorization`, so `shared` still compile-depends on no business module
- [x] The token's authority collection is **empty**, which is the whole mechanism: org permissions
      still resolve from the DB for the target (tenant endpoints work) while every
      `hasRole('platform-*')` fails (`/admin/**` is unreachable from inside a session)
- [x] `ImpersonationFilter` at `@Order(-2)` — after authentication, before rate limiting, idempotency
      and the provisioning gate, so the whole request sees ONE effective principal; the previous
      `SecurityContext` is restored in a `finally`
- [x] `CurrentUser` gains a nested `Impersonation(sessionId, actorSubject)` and
      `accountableSubject()`; `CurrentUserProvider` resolves both token types
- [x] **V18** `impersonation_session` — `BaseEntity`, **not** soft-deletable: the person a delete
      would serve is the operator whose reach the row records. Sessions *end*, they are never removed
- [x] **V19** `audit_log.on_behalf_of` + `impersonation_id`. Attribution inverts inside a session —
      `actor` is the operator, the worn identity moves to `on_behalf_of` — filled from the security
      context in `AuditLogImpl`, so the `AuditLog` port signature is unchanged and no call site can
      forget it
- [x] `POST`/`GET`/`DELETE /api/v1/admin/impersonations` (floor `platform-support`; `mode=WRITE`
      needs `platform-admin`; `?actor=` on the listing needs `platform-admin`), cursor-paginated
- [x] Guardrails in `ImpersonationService`: target exists, not `DISABLED`, not soft-deleted (404 and
      409 stay distinct); a platform-role holder needs `platform-superadmin`; `reason` ≥ 8 chars;
      TTL default 15 min / cap 30, over-cap **rejected** not clamped; one live session per
      (actor, target) with supersede audited; no self-impersonation
- [x] Kill switch `app.impersonation.enabled` — off means **refused**, not absent: the routes and
      the header both answer 403 naming the switch
- [x] **Gate:** support reaches tenant data only *with* the header and `/api/v1/admin/users` only
      *without* it — `ImpersonationReachTest.supportReachesTenantDataThroughASessionAndTheAdminSurfaceOnlyOutsideOne`
- [x] **Gate:** a read-only session refuses an unsafe method (`GET`/`HEAD`/`OPTIONS` are safe) —
      `ImpersonationReachTest.aReadOnlySessionRefusesAnUnsafeMethodAndNamesTheHeaderThatCausedIt`
- [x] **Gate:** an audited write from inside a session names the operator *and* the identity worn —
      `ImpersonationReachTest.anAuditRowFromInsideASessionNamesTheOperatorAndTheIdentityTheyWore`,
      `.aWebhookCreatedInsideASessionNamesTheOperatorAndTheSession`
- [x] **Gate:** every revocation kills the *very next* request — ending, expiry (no sweep job),
      demoting the operator, disabling the operator, deleting the target —
      `ImpersonationReachTest.endingASessionDeniesTheVeryNextRequest`,
      `.anExpiredSessionDeniesWithoutAnySweepJobHavingRun`,
      `.anOperatorWhoLosesTheirPlatformTierLosesTheSessionTheyHold`,
      `.demotingAWriteOperatorRefusesTheWriteCapableSession`,
      `.disablingTheOperatorsOwnAccountDeniesTheVeryNextImpersonatedRequest`,
      `.deletingTheTargetMidSessionDeniesTheVeryNextImpersonatedRequest`
- [x] **Gate:** a session id presented by a different actor is rejected, and a malformed one is a 403
      envelope rather than a 500 — `ImpersonationReachTest.aSessionIdPresentedByADifferentActorIsRejected`,
      `.aMalformedSessionIdIsAForbiddenEnvelopeNotAServerError`
- [x] **Gate:** the context is restored, including when the chain throws —
      `ImpersonationFilterTest.theContextIsSwappedForTheChainAndRestoredAfterwards`,
      `.aChainThatThrowsStillLeavesTheOperatorsOwnContextOnTheThread`,
      `ImpersonationReachTest.theRequestAfterAnImpersonatedOneSeesItsOwnIdentity`
- [x] **Gate:** a session never activates the target it wears (the one context with the provisioning
      gate switched on) — `ImpersonationProvisioningGateTest.aSessionNeverActivatesTheTargetItWears`
- [x] **Gate:** the kill switch refuses the feature rather than removing it —
      `ImpersonationDisabledTest.theRouteRefusesInsteadOfDisappearing`,
      `.theListingAndTheEndpointThatEndsASessionRefuseToo`,
      `.theImpersonateHeaderIsRefusedRatherThanIgnored`
- [x] **Gate:** full `./gradlew build` green — 42 impersonation tests across five classes;
      `ApplicationModules.verify()` and ArchUnit green, so `shared` gained no dependency on `identity`

## Identity reconciliation ✅ (2026-07-31)

Keycloak is the system of record for identity and `app_user` is a projection of it, but nothing pushes
a deletion from there to here — so without a pull the projection only grows and a deleted account
lingers as an `ACTIVE`-looking row. Access is never at risk from the lag (no-JIT provisioning, and a
deleted account cannot mint a token), so this corrects the **record**, it does not close a hole.

- [x] `IdentityReconciliationJob` — `@Scheduled` + `@SchedulerLock("identity-reconciliation")`, daily at
      02:00 (`app.scheduler.identity-reconciliation-cron`). Lives in `identity`, not `scheduler`,
      because it needs `KeycloakUserAdminGateway` and `UserRepository`, both module-internal
- [x] `KeycloakUserAdminGateway.accountPresence` is **tri-state** — `PRESENT` / `ABSENT` / `UNKNOWN`;
      a lookup that failed can never be read as a deletion
- [x] `IdentityReconciliationProperties`: `action` (ships as `REPORT`), `grace-period` PT1H,
      `max-orphan-ratio` 0.10, `batch-size` 500. Enabled/disabled by
      `app.identity.reconciliation.enabled` via `@ConditionalOnProperty`
- [x] One transaction per row (`TransactionTemplate`, not a self-invoked `@Transactional`): the status
      change and the audit row that explains it commit together
- [x] Audit action `identity.user_disabled_by_reconciliation`, with `actor` null — nobody did this, a
      scheduled comparison did
- [x] **Gate:** a deleted Keycloak account is disabled and audited —
      `IdentityReconciliationJobTest.anAccountDeletedInKeycloakIsDisabledAndAudited`
- [x] **Gate:** an inconclusive lookup revokes nobody, and a mass disappearance is treated as
      misconfiguration and changes nothing — `.anInconclusiveLookupNeverRevokesAnybody`,
      `.aMassDisappearanceIsTreatedAsMisconfigurationAndChangesNothing`
- [x] **Gate:** the shipped `REPORT` default finds the orphan without revoking it, an already-disabled
      row is not re-audited nightly, and a row inside the grace period is not examined —
      `.reportModeFindsTheOrphanWithoutRevokingIt`, `.anAlreadyDisabledAccountIsNotVisitedAgain`,
      `.anAccountInsideTheGracePeriodIsNotExamined`

## Audit remediation — phases 1 & 2 ✅ (2026-08-01)

From [reviews/2026-08-01-code-audit.md](reviews/2026-08-01-code-audit.md); phases 3–4 remain open.

- [x] **H1** `spring.servlet.multipart` declared (25MB, env-overridable) — uploads over 1MB work over
      HTTP again; `MultipartConfigContractTest` pins the relation to the 5MB multipart threshold
- [x] **M1** cached nulls survive the L2 round-trip (`enableSpringCacheNullValueSupport` + `NullValue`
      allowed by the type validator) — `ValkeyCacheIntegrationTest.aCachedNullSurvivesAnL2OnlyRead`
- [x] **M2** `TwoLevelCache` evicts L2 strictly before L1, closing the stale-refill window
- [x] **M3** idempotency `complete`/`release` fenced on the claim timestamp —
      `IdempotencyIntegrationTest.aStaleClaimantCannotDestroyTheTakeoversClaim`; the request hash now
      includes the query string (`.sameKeyWithDifferentQueryParametersIsAConflictNotAReplay`)
- [x] **M4** webhook success-path status write isolated — a DB blip after a delivered POST leaves the
      row PROCESSING for reclaim instead of re-POSTing or recording FAILED; javadoc states
      at-least-once honestly
- [x] **M5** DISABLED subscriptions pause queued deliveries and resume on re-enable —
      `WebhookDeliveryTest.disablingASubscriptionPausesQueuedDeliveriesUntilReenabled`
- [x] **M6/M7/M19** retention actually runs: `WebhookRetentionJob`, `NotificationRetentionJob`
      (replacing the worker-loop purge), `EventInboxPurgeJob` — nightly, ShedLock-guarded, batched,
      covering dead-letters too; one pinning test each
- [x] **M8/M9/M14** S3: multipart aborted on failure, explicit connect/socket/api-call timeouts
      (`app.storage.*`), no SDK type escapes the module
- [x] **M10** DuckDB staging table unique per run + orphan cleanup — concurrent same-report runs
      can no longer corrupt each other
- [x] **M11** concurrent same-email provisioning resolves to the winner (Keycloak 409 mapped)
- [x] **M12** a provisioned user / created org and its audit row commit in one transaction
- [x] **M13** reconciliation walks ALL candidates in ordered keyset pages inside a lease deadline and
      rethrows after isolating per-row failures —
      `IdentityReconciliationJobTest.aPassExaminesEveryCandidateAcrossMultiplePages`
- [x] **M17** (copies) `Role.getPermissions` defensive copy; L2 refills wrapped unmodifiable;
      `getCacheNames` copied
- [x] **M18** `app.idempotency.*` / `app.scheduler.*` declared in yaml and bound to validated records
      (negative retention fails at startup; inbox retention must cover the redelivery window)
- [x] **M22** workers stamp persisted times from the injected `Clock`
- [x] **V20** — indexes for the member/role/subscription listings, case-insensitive email lookup,
      `occurred_at` range filter, and the two retention scans
- [x] Pulled-forward LOWs: `Redirect.NEVER` pinned with why-comments, fan-out payload serialized
      once, UTF-8 invalidation decode, secret-default comments
- [x] **Gate:** full `./gradlew test` green over the final tree

## Localization module ✅ (2026-08-01)

Slice 1 of [NEXT_MODULES_PLAN.md](NEXT_MODULES_PLAN.md).

- [x] **V21** `translation` — soft-deletable (partial unique `(locale, msg_key)`), added to
      `PURGE_ORDER` (the metamodel-derivation test enforces the pairing)
- [x] `Messages` port with the contract fallback chain: exact tag → language → default locale →
      **the key itself** (a catalog gap renders, never throws)
- [x] Per-locale bundles cached L1+L2 via a separate `TranslationBundles` bean (the
      `PermissionResolver` self-invocation rule); every write/delete evicts + broadcasts
- [x] Locales normalized to lowercased BCP-47; unparseable tags are a 422 naming the parameter
- [x] REST: cursor-paginated listing + get/put/delete, writes `platform-admin`, audited with
      from→to (`localization.translation_changed` / `_deleted`); deletes publish
      `TranslationChanged` explicitly
- [x] **Gate:** fallback chain asserted step by step —
      `MessageResolutionTest.fallsBackExactThenLanguageThenDefaultThenKey`
- [x] **Gate:** write and delete both evict the cached bundle — `.writesAndDeletesEvictTheCachedBundle`
- [x] **Gate:** full `./gradlew test` green (`verify()` with 11 modules); OpenAPI + Modulith docs
      regenerated; DATA_MODEL §4.9 / SRS §3.15 + catalogue + traceability + config reference updated

## Search module ✅ (2026-08-01)

Slice 2 of [NEXT_MODULES_PLAN.md](NEXT_MODULES_PLAN.md).

- [x] **V22** `pg_trgm` + `search_document` — a rebuildable projection (deliberately NOT
      soft-deletable, the header argues it), GENERATED `tsv`, GIN on `tsv`, trigram GIN on titles
- [x] `SearchIndex` port (idempotent `(entity_type, entity_id)` upsert) + inbox-guarded listeners on
      `OrganizationRegistered` and `UserProvisioned`
- [x] FTS-then-trigram strategy (`websearch_to_tsquery` → `word_similarity` fallback); the cursor
      carries the mode so later pages never re-decide; ranks travel as float8 (the float4 text
      round-trip parses into a DIFFERENT double and would repeat page 1 — pinned in a why-comment)
- [x] Tenant search cut inside the SQL; platform-wide (null-org) rows admin-only; two controllers so
      the class-level mapping keeps `/admin/search` out of the `X-Impersonate` docs
- [x] **Gate:** isolation, fallback+cursor, redelivery-dedup, 422s —
      `SearchApiTest` (5 tests)
- [x] **Gate — measured, not adjectival:** 100k documents, 50 warm org-scoped queries —
      **p50 20ms / p95 35ms standalone** on the reference container; the asserted tripwires
      (p50<50, p95<150) carry headroom for full-suite neighbor load, and the test prints the
      measured figures every run — `SearchPerformanceTest.p95StaysUnderBudgetAcross100kDocuments`
- [x] **Gate:** full `./gradlew test` green (12 modules); docs regenerated; DATA_MODEL §4.10 /
      SRS §3.16 + catalogue + traceability updated

## Document module ✅ (2026-08-01)

Slice 3 of [NEXT_MODULES_PLAN.md](NEXT_MODULES_PLAN.md).

- [x] **V23** `document` — soft-deletable catalog over files-held keys (ninth soft-deletable,
      partial unique on `storage_key`), in `PURGE_ORDER`
- [x] `Documents` port + `NewDocument`/`DocumentRegistered` (published explicitly after save — the
      id is persist-assigned, so the aggregate could not have carried it)
- [x] Additive `document:read` / `document:manage` permissions — the startup reconciler hands them
      to existing OWNERs; a foreign org's document is 404, never 403
- [x] Personal surface tiered by blast radius exactly like files: support reads across users,
      admin deletes
- [x] Delete is bytes-now / row-soft, object first (remote, outside the tx) then row + audit +
      un-indexing in one transaction — the ordering and its crash story are in the service javadoc
      and the migration header
- [x] Search tie-in proven: titles indexed on register, un-indexed on delete (the reference
      `SearchIndex` producer)
- [x] **Gate:** round-trip incl. 302-presigned download + delete semantics + audit sequence, org
      isolation, permission split, personal tiering — `DocumentApiTest` (4 tests; storage mocked at
      the port — the files module's own IT pins real S3 semantics)
- [x] **Gate:** full `./gradlew test` green (13 modules); docs regenerated; DATA_MODEL §4.11 /
      SRS §3.17 + catalogue + traceability updated

## P2 — API keys ✅ (2026-08-01)

Slice 2 of [PLATFORM_EXPANSION_PLAN.md](PLATFORM_EXPANSION_PLAN.md).

- [x] **V29** `api_key` (soft-deletable, FOURTEENTH — revocation IS the soft delete); `secret_hash`
      SHA-256 (hashed not encrypted: we only verify, never need the plaintext back); org keys carry
      a permission subset, platform keys a support tier; partial unique on live prefix
- [x] `ApiKeyAuthenticationFilter` (shared, before the bearer filter) + `ApiKeyAuthenticator` port
      (the `OrgAuthorization` seam) + `ApiPermissionEvaluator` machine branch (subset ∩ strict
      org-id, never roles); `CurrentUserProvider` resolves the key principal
- [x] Subset cap at mint (reuses `OrgAuthorization.permissions` — a key never out-ranks its
      creator); constant-time hash compare; expiry; throttled usage stamp off the auth path
- [x] Org surface (`apikey:manage`, added to `Permission` — OWNER auto-inherits) + platform surface
      (support-tier, platform-admin-minted); secret shown once; revocation immediate; audited
- [x] **Gate:** subset reached / beyond-subset 403 / foreign-org 403 / revoke→401 / bad-secret→401
      (no prefix oracle) / escalation refused / platform key reads support not admin —
      `ApiKeyAuthTest` (3 tests). OpenApiConfig: two `API keys` tags + map entries (flagged)

## Odds-and-ends + API guide + expansion plan + P1 profile ✅ (2026-08-01)

- [x] `SettingChanged` gained `occurredAt` (the last event without it — EVENTS.md rule now uniform)
- [x] Webhook `events` typed as an ENUM in the OpenAPI spec via a module-local `OpenApiCustomizer`
      reading `WebhookEventType` itself (can never drift; no hardcoded `allowableValues`)
- [x] Billing view lists the account's payment METHODS (read-only — adding one stays Kaui's /
      a KB payment plugin's job)
- [x] **docs/api-guide.html** — self-contained tester guide: auth + axes + org switching, envelope,
      localized errors, cursors, rate limits, idempotency, and per-surface walkthroughs with
      sample data (tenant lifecycle, members + escalation 403, plans/entitlements, billing,
      documents, search, the full exchange import walkthrough with error report, webhook
      signature verification, audit, impersonation) + error-code table
- [x] **docs/PLATFORM_EXPANSION_PLAN.md** — the strategy for the ten remaining workstreams
      (P1 profile → P2 api-keys → P3 groups → P4 devices+policies → P5 integration hub →
      P6 compliance → P7 maintenance → P8 support), models + endpoints + gates + V-numbers
- [x] **P1 shipped — profile module (V28)**: `user_profile` (soft-deletable, THIRTEENTH) +
      contacts as element rows + `user_preference` composite-PK pairs; get-or-default profile,
      additive preferences (null deletes), avatar via the files port (image/* 2 MB,
      old-object-deleted-last), read-only linked accounts (`UserDirectory.linkedAccounts` over
      Keycloak federated identities), `GET /api/v1/me/organizations` in the ORGANIZATION module
      (dual-member switch list; the switch is a token act), support profile read.
      OpenApiConfig: `Shared · My profile` tag + three map entries (flagged) —
      `ProfileApiTest` (4 tests)

## Billing & payments — Kill Bill ✅ (2026-08-01)

- [x] **V27** `billing_account` — the org ↔ Kill Bill linkage projection (soft-deletable, TWELFTH;
      partial unique on live org; `kb_account_id` index for callback resolution). Kill Bill is the
      billing system of record; nothing financial stored locally
- [x] `KillBillGateway` — timeout-bounded RestClient (basic auth + tenant key headers +
      `X-Killbill-CreatedBy`), Location-header id idiom, idempotent `ensureAccount` by externalKey
      (create races resolve to the winner), subscriptions/invoices/balance reads, tenant +
      simple-plan + callback bootstrap
- [x] One write direction: KB events → `BillingService.reconcile` → the NEW
      `subscription.Subscriptions` port (`assignPlan`/`markStatus`) — the same audited paths the
      admin surface uses; `INVOICE_PAYMENT_FAILED` → `PAST_DUE` (grace, entitlements kept),
      recovery → `ACTIVE`, no billable subscription → FREE
- [x] Token-authenticated `@Hidden` callback endpoint (permit-listed; constant-time compare; 401
      before any read; unknown events acknowledged; transient failures 5xx so KB retries)
- [x] Surfaces: admin provision/view/subscribe/invoices (`Platform · Billing & plans`), tenant
      invoices (`Organization · Billing` — one more OpenApiConfig tag, flagged); plan-mapping
      config (FREE deliberately unmapped)
- [x] docker-compose: killbill (8082) + killbill-db (mariadb) + kaui (9095); `.env` +
      LOCAL_ACCESS + TENANT_LIFECYCLE updated; `BILLING_BOOTSTRAP` dev opt-in
- [x] **Gate:** mocked-gateway flows (`BillingApiTest`: idempotent provision + audit, PRO
      reconcile through the port, PAST_DUE→ACTIVE flips, bad token 401, invoices both surfaces,
      FREE unbillable 422) + REAL Kill Bill wire (`KillBillIntegrationTest`: killbill + mariadb
      containers — tenant, simple plan, account idempotency, ACTIVE subscription, invoices)

## Open-items close-out ✅ (2026-08-01)

The four "deliberately open" items, closed:

- [x] `ExchangeRetentionJob` — terminal jobs past `app.exchange.retention` (P30D) purge nightly,
      batched over the V24 terminal index, ShedLock `exchange-job-retention`, purge counter;
      error rows cascade, ARTIFACTS deliberately keep the document lifecycle —
      `ExchangeRetentionJobTest`
- [x] `JobCompleted` — published explicitly from the worker's terminal READ (the fenced row is the
      truth the event repeats; a crash between write and publish loses only the event, never the
      row), inside a small tx for the registry. Consumers: `ExchangeJobCompletionNotifier`
      (in-app to the requester, `EventInbox`-idempotent) and the webhooks fan-out
      (`org.exchange.job_completed` with outcome + counters) — `ExchangeJobCompletedFlowTest`
- [x] Webhook signing secrets **encrypted at rest** — AES-256-GCM `enc:v1:` via `SecretCipher`
      (key: `app.webhooks.secret-encryption-key`, SHA-256-derived; dev default in yaml, ALWAYS
      override in prod), plaintext exists exactly once (the create response), sender decrypts only
      to sign, startup migrator rewrites pre-encryption rows idempotently. Encryption, NOT
      hashing — HMAC needs the plaintext, so a hash would break signing —
      `WebhookSecretEncryptionTest` + the signature-verified delivery test now also proves
      decrypt-before-sign
- [x] `GET /api/v1/webhooks/event-types` — the subscribable vocabulary on the wire, with
      descriptions (enum-backed, so it can never drift from what create/update accept) —
      `WebhookEventTypesApiTest`

## Guideline completion + tenant lifecycle + subscriptions ✅ (2026-08-01, overnight)

Zero-deferral order: nothing in `reusable-data-exchange-platform-guidelines.md` left unimplemented,
plus the platform tenant surface and a subscriptions module — with the three-reviewer final audit's
confirmed findings fixed in the same pass.

- [x] **V25** exchange completion: XLSX codec (SAX streaming read via a vthread bridge, SXSSF
      streaming write), XML codec (StAX, XXE-disabled), ZIP source unwrap (magic-byte sniffed),
      template versioning (`templateVersion()` on the SPI, stamped per job) + downloadable
      templates (an empty export — one code path for all four formats), recurring export schedules
      (cron/UTC, ShedLock firing job, revocation DISABLES loudly), retry backoff between claim
      generations, `exchange.job_submitted`/`_cancel_requested`/schedule audit rows,
      `org_id` tightened NOT NULL
- [x] Localized error details: `Messages` port moved to `shared.i18n` (kernel-port pattern);
      `ErrorDetailLocalizer` resolves `error.<code>` per Accept-Language in BOTH error paths
      (MVC handler + filter writer); catalog gap keeps the author's English — proven in
      `LocalizationApiTest.errorDetailsLocalizeThroughTheCatalogByAcceptLanguage`
- [x] **Audit hardening (the escapee hunt's confirmed findings)**: mid-batch heartbeat + claim-time
      reclaim cap (the two HIGHs — slow per-record remote work can no longer get a healthy claimant
      double-claimed, and a reclaim loop dies loudly); Keycloak per-record 4xx reclassified as DATA;
      malformed CSV mid-file → curated FAILED, one attempt; truncated-on-resume source → FAILED,
      never a silent COMPLETED; error report streamed with a real fetchSize; cancelled runs upload
      their report; `SKIPPED` outcomes counted; dead-URL guard on document + exchange downloads;
      upload validates metadata BEFORE storing bytes (422, no orphaned object); personal-doc
      404-oracle closed (tiered: support gets the honest 403); translation PUT race resolves to the
      winner; `MessageFormat` can no longer break the never-throws contract; evict keys use
      `Locale.ROOT`; search erasure residue swept nightly (users/orgs un-indexed once their rows are
      hard-purged); notification unknown-channel dead-letters counted; export metrics count only the
      winning terminal write; HTTP p95 panel now queries the metric this app actually emits
      (+ percentiles-histogram config); purge-silence alert rewritten as `absent_over_time`;
      filter order renumbered (impersonation −2 → org-MDC −1 → rate limit 0 → idempotency 1 →
      provisioning gate 2) so 429s and replays carry the tenant
- [x] Platform tenant surface + lifecycle (docs/TENANT_LIFECYCLE.md): `GET /api/v1/admin/orgs`
      (+`?status=`), `GET /{id}`, `GET /{id}/members`, `DELETE /{id}` (SUSPENDED-only, soft,
      audited, `OrganizationDeleted` → cache evict + `org.deleted` webhook; Keycloak org kept
      deliberately) — `AdminOrganizationApiTest`
- [x] **V26** subscriptions: seeded FREE/PRO/ENTERPRISE catalog, one live subscription per org
      (none = FREE), `Entitlements` gating port wired into member invite (pre-provisioning),
      webhook create, exchange submit + schedule cap; upgrade-shaped 403s;
      `SubscriptionChanged` evicts the entitlement cache (a downgrade cannot ride the TTL) and
      fans out `org.subscription_changed` — `SubscriptionGatingTest` (Awaitility on the async
      evictor)
- [x] OpenApiConfig additions (user's file — flagged): three tag constants + declarations
      (`Organization · Exchange schedules`, `Platform · Billing & plans`, `Shared · Exchange
      catalog`) and six curated-map entries; soft-deletables now ELEVEN (exchange_schedule,
      org_subscription) with every count bumped; FK total six; next free migration **V27**
- [x] **Gate:** full `./gradlew test` green; DATA_MODEL §4.12–4.13 / SRS §3.18–3.19 + FR-SUB +
      endpoint catalogue + TENANT_LIFECYCLE + EVENTS + ARCHITECTURE updated

## Observability pass ✅ (2026-08-01)

Slice 5 of [NEXT_MODULES_PLAN.md](NEXT_MODULES_PLAN.md) — numbers someone can alert on, plus the
dashboards to see them. (The plan's "RateLimit headers on success" item turned out already shipped
by the audit remediation; verified, not re-done.)

- [x] Custom Micrometer counters at every give-up/refusal point — the full catalogue with tags and
      increment sites is SRS §5.6: `smsone.deliveries.dead_lettered` (both workers, all four
      notification reasons), `smsone.ratelimit.denied`, `smsone.exchange.jobs`/`.records` (records
      counted only on batch COMMIT — replays never double-count), `smsone.cache.requests`
      (pre-registered — the lookup is on the request hot path), `smsone.impersonation.sessions`,
      `smsone.purge.deleted` (job+table; the event-publication purge stays uncounted — the
      framework API returns void)
- [x] MDC attribution: `org_id` on every org-scoped request (`OrgMdcFilter`, the request-id
      filter's pattern applied to WHO); `org_id`/`exchange_job_id`/`exchange_handler` around the
      whole of an exchange job run
- [x] Two Grafana dashboards file-provisioned into otel-lgtm (`docker/grafana/`, folder SMSOne):
      deliveries & jobs (dead-letter rate, exchange outcomes/throughput, purge activity) and
      API & cache (HTTP p95 by route, 429s by tier, hit ratio, impersonation trend) + example
      alert rules in `docker/grafana/README.md` (dead-letters, 429 pressure, purge silence,
      failed jobs, breaker open)
- [x] **Gate:** counters asserted through their REAL paths — the webhook dead-letter after 5 real
      503s, the edge-filter 429, the retention purge's row count, and the exchange
      jobs/records deltas (`WebhookDeliveryTest`, `RateLimitIntegrationTest`,
      `WebhookRetentionJobTest`, `ExchangeApiTest`)
- [x] **Gate:** full `./gradlew test` green; SRS §5.6 (NFR-OBS-7..9 + meter catalogue),
      LOCAL_ACCESS, COMPLETED_MODULES updated

## Exchange module ✅ (2026-08-01)

Slice 4 of [NEXT_MODULES_PLAN.md](NEXT_MODULES_PLAN.md) — the scale-first centerpiece. As-shipped
deltas from the plan sketch: submit answers **202** (not 201 — the work is a background job), the
surface is org-scoped (`/api/v1/orgs/{orgId}/exchange/**`), the SPI settled as
`importRecord`/`export` with per-direction permissions, no `total` column (it would cost a full
extra pass over the file; `processed`/`failed` + terminal status carry progress), and row errors
live in `exchange_job_error` rather than worker memory so reports survive crashes. One structural
consequence: the `Documents` port moved to `shared.document` (the `AuditLog` pattern) — with
`organization` implementing the exchange SPI, a compile edge from exchange into `document` would
have closed the cycle `document → search → organization → exchange`, and `ModularityTests` said so.

- [x] **V24** `exchange_job` (fenced `attempts`, `next_offset` resume point, heartbeat `locked_at`,
      partial claim/terminal indexes) + `exchange_job_error` (durable row errors, PK
      `(job_id, row_num)`, cascade FK) — queue species, deliberately not soft-deletable
- [x] `ExchangeHandler` SPI + `ExchangeContext`/`ImportOutcome`/`InvalidRecordException`/
      `RecordWriter`; streaming `FormatCodec` internals (commons-csv RFC-4180, JSONL)
- [x] §7 queue discipline end to end: `SKIP LOCKED` claim (one job per claim), every write fenced,
      progress = counters + offset + error rows in ONE transaction + heartbeat, stale reclaim,
      curated `last_error` (real causes to logs only)
- [x] Data vs infrastructure failures kept apart: invalid records → durable row errors →
      `COMPLETED_WITH_ERRORS` + `row_number,error` report; anything else → batch abandoned, retry
      up to `max-attempts`, then `FAILED`
- [x] Two-layer authorization: programmatic submit gate on the handler's per-direction permission
      (mirrors `ApiPermissionEvaluator`'s active-org strictness); per-record escalation resolves the
      REQUESTER at processing time (`PermissionEscalationGuard.requireSubjectHolds`,
      `MemberService.inviteAs`); artifacts download to the requester or permission holders only
- [x] Reference `org-members` handler in `organization.internal` driving the same `MemberService`
      as REST; export re-importable (emails via `UserDirectory.emailsBySubjects` batch lookup)
- [x] All artifacts (source, report, result) registered as `EXCHANGE` documents via the port
- [x] **Gate — the scale contract:** 100k-row CSV import crashed mid-run at record 30,050 resumes
      from the committed offset 30,000 — 2 attempts, `processed=100000`, 100,050 handler calls
      (at-least-once delivery), 100,000 distinct effects (exactly-once) —
      `ExchangeResumeTest.aCrashedImportResumesFromItsCommittedOffsetWithExactlyOnceEffects`; plus
      stale-claim reclaim and both cancellation edges (same class)
- [x] **Gate:** REST lifecycle — 403 without the handler permission, 202 → drain →
      `COMPLETED_WITH_ERRORS` with row-addressed report behind a 302, export round-trip with
      permission-gated result, foreign org 404, discoverable handler catalog — `ExchangeApiTest`
      (5 tests); requester-escalation + idempotent replay + roster export —
      `MembersExchangeHandlerTest` (2 tests)
- [x] **Gate:** full `./gradlew test` green (14 modules); docs regenerated; DATA_MODEL §4.12 /
      SRS §3.18 + catalogue + endpoint table updated; stale "next free migration" pointers fixed
      across AGENTS/DATA_MODEL/SRS

## Audit remediation — phases 3 & 4 ✅ (2026-08-01)

- [x] **M15** controllers are thin again: `AuditQueryService` (readOnly) behind `AuditController`;
      `UserAccessService` owns the identity module's reads for `/me` and `/admin/users`;
      `MemberService.roleCodes` (id→code projection) behind the member listing;
      `SchedulerController`'s inline framework-table read carries its why
- [x] **M16** role + webhook-subscription listings cursor-paginated (additive: `meta.page` appears,
      `data` stays an array)
- [x] **M20** settings/event-purge tests live in their `internal` packages; `FeatureFlag`,
      `FeatureFlagService`, `EventPublicationPurgeJob`, `NotificationProperties`,
      `AnalyticsProperties` demoted; `Setting`/`SettingService` stay public with the forcing tests
      named in their javadoc
- [x] **M21** startup role-catalog reconciler scans in keyset pages, not `findAll()`
- [x] **M23** (rest) member listing maps role codes via one two-column query, not 1+N EAGER loads
- [x] Phase-4 LOW sweep: breaker-open → quiet 503 `SERVICE_UNAVAILABLE` (+ 413/429/503 `mapStatus`
      branches); `Cursors` escaping (+ test); `SafeOutboundUrl` 6to4/Teredo unwrap (+ tests);
      idempotent no-op guards on settings/flags/org lifecycle; dead `MembershipStatus.SUSPENDED`
      removed; impersonation `end` answers 404 to non-admin probes; single target read per
      impersonated request; `FileController` tuned numbers moved to `StorageProperties`; UTF-8 +
      nanoTime + memoized-bucket fixes in shared; Awaitility replaces both hand-rolled poll loops;
      javadoc floors + docs-exposure acceptance written down
- [x] **Gate:** full `./gradlew test` green over the final tree

---

**Phases 0–4 + notification module complete and gated.** (Next free migration: **V27**.) Completed modules: [COMPLETED_MODULES.md](COMPLETED_MODULES.md).

| Reference | What it is |
|---|---|
| [AGENTS.md](../AGENTS.md) | Engineering standards — §1 is the rules that fail the build, §14 the review checklist |
| [DATA_MODEL.md](DATA_MODEL.md) | Every table, column, index and invariant, plus migration history |
| [SRS.md](SRS.md) | Functional/non-functional requirements with IDs and a traceability matrix |
