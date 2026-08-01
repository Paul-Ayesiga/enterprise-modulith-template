# Completed Modules

What is **built, tested, and gated** today; this file lists only finished modules. Every module owns its data,
talks to others through events, and is boundary-verified by Spring Modulith (`./gradlew test`).

_Last updated: 2026-08-01._

## Summary

| Module | Purpose | REST endpoints | Publishes events | Status |
|---|---|---|---|---|
| **shared** (kernel) | Cross-cutting foundation reused by every module | envelope/error/probes | — | ✅ |
| **settings** | System settings + feature flags | `/api/v1/settings`, `/api/v1/feature-flags` | `SettingChanged`, `FeatureFlagChanged` | ✅ |
| **localization** | Translation catalog + `Messages` resolution port | `/api/v1/translations/**` | `TranslationChanged` | ✅ |
| **search** | Postgres FTS projection + `SearchIndex` port | `/api/v1/orgs/{orgId}/search`, `/api/v1/admin/search` | — (consumes `OrganizationRegistered`, `UserProvisioned`) | ✅ |
| **document** | Managed-file catalog over the files port + `Documents` port | `/api/v1/orgs/{orgId}/documents/**`, `/api/v1/documents/**` | `DocumentRegistered` | ✅ |
| **exchange** | Data Exchange Platform — durable, resumable import/export jobs behind the `ExchangeHandler` SPI (CSV/JSONL/XLSX/XML + ZIP, templates, recurring schedules) | `/api/v1/orgs/{orgId}/exchange/**`, `/api/v1/exchange/handlers/**` | — (drives domain services through the SPI) | ✅ |
| **subscription** | Plan catalog + per-org subscription + the `Entitlements` gating port | `/api/v1/admin/orgs/{id}/subscription`, `/api/v1/admin/plans`, `/api/v1/orgs/{orgId}/subscription` | `SubscriptionChanged` | ✅ |
| **files** | S3-compatible object storage | `/api/v1/files` | — | ✅ |
| **scheduler** | Clustered scheduled jobs | `/api/v1/scheduler/locks` | — | ✅ |
| **analytics** | Embedded OLAP / reporting | `/api/v1/analytics/reports` | — | ✅ |
| **notification** | Multi-channel, pluggable delivery | `/api/v1/notifications` | — (consumes `FeatureFlagChanged`) | ✅ |
| **identity** | Admin-driven user provisioning (no JIT) + audited impersonation | `/api/v1/me`, `/api/v1/admin/users`, `/api/v1/admin/impersonations` | `UserProvisioned`, `UserActivated` | ✅ |
| **organization** | Keycloak orgs + org-scoped RBAC authority | `/api/v1/orgs/**`, `/api/v1/permissions` | `OrganizationRegistered`, `MembershipCreated`, `MembershipRoleChanged`, `MemberRemoved`, `RolePermissionsChanged`, `OrganizationStatusChanged` | ✅ |
| **audit** | Append-only audit trail (who/when/where/what/from→to) | `/api/v1/audit`, `/api/v1/orgs/{orgId}/audit` | — (records via the `AuditLog` port) | ✅ |
| **webhooks** | Per-org outbound event subscriptions (signed, durable) | `/api/v1/orgs/{orgId}/webhooks/**` | — (consumes org events) | ✅ |

---

## shared (kernel)
The reusable enterprise foundation — not a business module, but the substrate they all build on.
- **Web contract**: unified JSON:API-style envelope (`{data|errors, meta, links}`), `meta.requestId` on every response + `X-Request-Id` header, cursor pagination (`page[size]`/`page[after]`).
- **Errors**: `ErrorCode` registry + `ApiException` hierarchy, global handler, **no stack traces on the wire**, RFC 9457 (`application/problem+json`) via content negotiation.
- **Security**: OAuth2 Resource Server validating Keycloak JWTs **including the `smsone-api` audience** (foreign-client/service-account tokens are rejected); realm roles → `ROLE_<role>`, client roles namespaced → `ROLE_<client>_<role>` (a client role named `platform-admin` can never satisfy the realm-role gate); `@EnableMethodSecurity`, `CurrentUser` + `PermissionEvaluator` seam.
- **Persistence**: `BaseEntity`/`AggregateRoot`, UUID keys, JPA auditing, soft-delete, keyset cursors — real Postgres 18 only (no H2).
- **Observability**: Actuator liveness/readiness probes, OTLP export, structured JSON logs carrying `requestId` + `traceId` + `org_id` (tenant-scoped requests and exchange job runs), virtual threads. Custom Micrometer counters at every give-up/refusal point — dead-letters (both queues), 429s by tier, exchange jobs/records, cache hit/miss, impersonation lifecycle, purge deletions (catalogue: SRS §5.6) — with two Grafana dashboards file-provisioned into the dev otel-lgtm (`docker/grafana/`).
- **Caching**: two-level Caffeine (L1) + Valkey (L2) with cross-instance invalidation and graceful L2-outage degradation.
- **Reliability**: idempotency-key store, event **outbox** (Modulith registry) + **inbox** (`EventInbox`), Resilience4j circuit breaker.
- **Rate limiting**: distributed token-bucket (Bucket4j over Valkey). Edge filter on `/api/**` — per-route tiers keyed **per active-org (the Keycloak `organization` claim) → flat tenant-claim → principal → IP**, `429` with the unified envelope + `Retry-After` + `RateLimit`/`RateLimit-Policy` (draft-ietf) + legacy `X-RateLimit-*`, **fail-open** on backend outage. The same `DistributedRateLimiter` powers the notification **egress** per-channel provider limits.

## settings
System-wide configuration and feature flags (feature flags replaced Togglz — no Boot 4 build).
- **Endpoints**: `GET/PUT /api/v1/settings[/{key}]`, `GET/PUT /api/v1/feature-flags[/{key}]` (writes require `platform-admin`; lists are cursor-paginated).
- **Events**: `SettingChanged(key, value)`, `FeatureFlagChanged(key, enabled)` — published via the DB-backed registry.
- Hot-path `isEnabled(key)` is cached; unknown flags are OFF, never an error.

## localization
Message localization behind the `Messages` port.
- **Resolution contract**: exact BCP-47 tag → language → `app.localization.default-locale` → the key
  itself — a missing translation renders its key, it never throws. Arguments via `MessageFormat` in
  the resolved locale.
- **Caching**: one bundle per locale in the two-level cache (separate `TranslationBundles` bean so
  the fallback walk stays on the proxy); every write/delete evicts + broadcasts cluster-wide.
- **Endpoints**: `GET /api/v1/translations[?locale=]` (cursor-paginated), `GET/PUT/DELETE
  /api/v1/translations/{locale}/{key}` — writes `platform-admin`, all changes audited with from→to.
  Locales stored lowercased (tags are case-insensitive); unparseable tags 422.
- Soft-deletable (`V21`), eighth of eleven in `PURGE_ORDER`; deletes publish `TranslationChanged`
  explicitly.

## search
Lightning-fast full-text search on Postgres — no new engine, and the speed is a measured gate.
- **Projection**: `search_document` with a GENERATED `tsvector` (GIN) + trigram GIN over titles;
  rebuildable by design, so it is not soft-deletable and has no purge job — producers own removal.
- **Feeding**: the `SearchIndex` port (idempotent upsert per `(entity_type, entity_id)`) plus
  inbox-guarded listeners on `OrganizationRegistered` (org-scoped) and `UserProvisioned`
  (platform-wide, admin search only).
- **Query**: `websearch_to_tsquery('simple')` ranked by `ts_rank_cd`, trigram `word_similarity`
  fallback for prefixes/typos; the cursor carries the chosen mode; ranks cross the wire as float8
  (float4's shortest text form parses into a different double — keyset page 2 would repeat page 1).
- **Endpoints**: `GET /api/v1/orgs/{orgId}/search` (`org:read`, tenant cut inside the SQL) and
  `GET /api/v1/admin/search` (`platform-support`, reaches null-org rows).
- **Verified at scale**: 100k documents, 50 warm org-scoped queries — **p50 20ms / p95 35ms
  standalone** on the reference container; the asserted tripwires (p50<50, p95<150) carry headroom
  for full-suite neighbor load, and the gate test prints the measured figures every run.

## document
The business record OF a stored file, over keys the `files` module holds.
- **Catalog**: name, owner subject, org (null = personal), provenance (`UPLOAD` | `EXCHANGE` via the
  `Documents` port — the exchange platform files its artifacts here). Soft-deletable, ninth in
  `PURGE_ORDER`.
- **Scoping**: org documents behind the additive `document:read`/`document:manage` pair (reconciled
  onto existing OWNERs at startup); personal documents tier by blast radius like files —
  support reads across users, admin deletes.
- **Delete, asymmetric by design**: the object goes immediately (remote, outside the tx), the row
  soft-remains with its audit trail — a restore recovers the record, never the content.
- **Search tie-in**: titles registered/un-indexed through the `SearchIndex` port — the port's
  reference producer.
- **Endpoints**: org `POST/GET` list, `GET /{id}` (302 presigned), `DELETE /{id}`; personal
  equivalents under `/api/v1/documents`.

## exchange
The Data Exchange Platform ([the guidelines doc](reusable-data-exchange-platform-guidelines.md) is
its spec): import/export as durable, resumable background jobs — built for scale first.
- **Queue discipline**: `exchange_job` is a §7-style claim queue (`SKIP LOCKED`, every write fenced
  on the claim's `attempts`, stale-lock reclaim); one job per claim — fan-out is instances sharing
  the queue. Progress commits counters + `next_offset` + the batch's error rows in ONE transaction
  and doubles as the heartbeat, so a crashed job **resumes from its last committed batch, never
  restarts** — proven by a 100k-row import killed mid-run (2 attempts, zero duplicate effects).
- **Two failure species, never confused**: `InvalidRecordException` is DATA — row-addressed into a
  durable `exchange_job_error` table (idempotent PK, so replays rewrite nothing), streamed into a
  `row_number,error` CSV report, never retried. Anything else is INFRASTRUCTURE — the batch is
  abandoned and the job retries up to `max-attempts`; `last_error` stays curated, causes go to logs.
- **The domain seam**: business modules implement `ExchangeHandler` (id, per-direction permissions,
  header template, idempotent `importRecord`, streaming `export`). The reference `org-members`
  handler drives the SAME `MemberService` as REST, and each record's escalation guard resolves the
  REQUESTER'S permissions at processing time — revocation between submit and run bites.
- **Formats are additive**: streaming `FormatCodec` SPI; CSV (commons-csv, RFC-4180) and JSON Lines
  ship; a new format touches no business logic.
- **Artifacts are documents**: source, error report and export result all register through the
  `Documents` port as `EXCHANGE` provenance — tenants browse their exchange history as documents.
- **Endpoints**: `POST …/exchange/imports|exports` (202 + pollable job), `GET …/jobs[/{id}]`
  (progress), `POST …/{id}/cancel` (batch-boundary, best-effort), `GET …/{id}/report|result`
  (302 presigned), `GET /api/v1/exchange/handlers` (the catalog with header templates).

## subscription
The commercial axis of a tenant — plans, subscriptions, and the gates everything else consults.
- **Catalog**: FREE/PRO/ENTERPRISE seeded create-if-absent (`PlanSeeder`); entitlement encoding:
  key present = feature on, positive value = cap, absent = off/unlimited (ENTERPRISE has no caps).
- **Gating**: the `Entitlements` port, resolved per org (no subscription row = FREE) and cached;
  wired into member invite (pre-provisioning), webhook create, exchange submit + schedule create —
  refusals are upgrade-shaped 403s, never permission-shaped.
- **Change bites immediately**: assigning a plan publishes `SubscriptionChanged` → the entitlement
  cache evicts (a downgrade cannot ride the TTL) and `org.subscription_changed` fans out to the
  tenant's webhooks. Assignments are `platform-admin` and audited.
- **Lifecycle doc**: [TENANT_LIFECYCLE.md](TENANT_LIFECYCLE.md) — states, transitions, and the
  platform surface (`/api/v1/admin/orgs/**`) that drives them, including SUSPENDED-only delete.
- Payment processing out of scope BY DESIGN — the module is the entitlement authority; billing
  integrates through the same admin endpoint.

## files
Object storage behind a single S3 abstraction (AWS SDK v2).
- **API**: `FileStorageProvider` — put/get/delete/exists, presigned GET/PUT, multipart.
- One code path drives local **SeaweedFS**, self-hosted, or managed **S3/R2/B2** (only endpoint + creds change).
- **Endpoints**: `POST /api/v1/files` (multipart upload), `GET`/`DELETE /api/v1/files/{key}`, `POST /api/v1/files/presign`. Keys are namespaced per caller (`u/<sub>/…`); read/delete/presign are owner-scoped, with cross-namespace access tiered by blast radius (`platform-support` reads, `platform-admin` deletes). Download 302s to a short-lived presigned URL, so bytes stream straight from storage (correct content-type, no app proxying).
- Verified against real SeaweedFS 4.40 (put/get/delete/presign/11 MB multipart); the REST surface is covered with the provider mocked.

## scheduler
Scheduled jobs that fire exactly once across all instances.
- `@Scheduled` + **ShedLock** (JDBC provider, `usingDbTime`).
- Ships the event-publication-registry purge, the idempotency-key purge and the **soft-delete retention purge** (`SoftDeletePurgeJob` — hard-deletes rows past `app.persistence.soft-delete.retention`, children before parents, isolating a failure per table so one poisoned FK cannot starve the tables after it).
- **Endpoints**: `GET /api/v1/scheduler/locks` (`platform-support`) — read-only observability into the ShedLock rows (which instance holds/last held each job's lock). No trigger endpoint: jobs are time-driven by design.

## analytics
Embedded OLAP for dashboards/KPIs/reports, in-process (no server).
- **Engine**: DuckDB 1.5.5.0 behind an `AnalyticsEngine` seam; thread/memory caps; native Parquet snapshots.
- Marts materialized from Postgres with exact `DECIMAL` money fidelity and UTC-pinned day buckets; atomic staging swap (a failed refresh leaves the old mart intact).
- **Endpoints**: `GET /api/v1/analytics/reports[/{code}]` (`platform-support`) — a **fixed catalog** of curated reports; clients pick a report by code and never supply SQL (the engine's contract: developer-authored analytics SQL only). Each run materializes its mart from Postgres, then aggregates.

## notification
Multi-channel, **pluggable** delivery of messages to recipients.
- **Channels** (each a `NotificationChannelSender` bean — add one to extend): **Email** (SMTP/Mailpit), **In-app** (persisted per immutable Keycloak subject, read via REST), **Webhook** (HTTP POST with an **SSRF guard** — private/loopback hosts refused unless `webhook-allow-private-hosts`), **Slack** (incoming-webhook POST), **SMS** (dev stub — drop in Africa's Talking/Twilio and set `app.notification.sms.stub=false`). HTTP sends carry a whole-exchange deadline (a hung receiver can't stall the pipeline); `4xx` responses dead-letter immediately (permanent), `5xx/408/429` retry with backoff.
- **Public API**: `Notifications` facade (`dispatch(NotificationRequest)`, `notifyAdmins`) with per-recipient channel addressing (`Recipient`).
- **Endpoints**: `GET /api/v1/notifications` (current user's in-app, cursor-paginated), `POST /api/v1/notifications/{id}/read`.
- **Breaking change (2026-07)**: in-app rows are now keyed by the Keycloak *subject*, not `preferred_username` (mutable/recyclable names leaked notifications on reuse). Rows written before this change are orphaned — no automatic backfill is possible (the app never stored usernames↔subjects); purge or remap them against Keycloak manually if that history matters.
- **Event-driven**: `@ApplicationModuleListener` on `FeatureFlagChanged` notifies admins (email + in-app), idempotent via `EventInbox`.
- **Async, scalable fan-out**: `dispatch` is non-blocking — it enqueues one row per recipient/channel into `notification_delivery`, then a background worker claims batches with `SELECT … FOR UPDATE SKIP LOCKED` and sends on a bounded pool of **virtual threads** (Java 21), **outside any DB transaction**, with exponential-backoff **retry**, **dead-lettering**, and stale-lock recovery. SKIP LOCKED lets N instances share the queue with no double-sends → fans out to thousands without blocking the caller. One channel/recipient failing never aborts the rest.
- **Per-channel egress limits**: before a send, a cluster-wide (Valkey) token bucket per channel throttles downstream providers (SMS/SMTP/…) — throttled deliveries are deferred, not failed (attempt not burned). Configure `app.notification.delivery.rate.<CHANNEL>`.
- Verified against real Mailpit (email), a real webhook receiver (fan-out), a **300-recipient concurrency test** (each hit exactly once, no duplicates), and a **per-channel throttle test** (2 of 5 sent, 3 deferred).

## identity
Business projection of Keycloak users + **admin-driven provisioning — no JIT** (a valid JWT is *not* access).
- **Provisioning** (`UserProvisioning` public port): create-or-reuse the Keycloak user via the Admin API, issue **temporary credentials** (`execute-actions-email` — the admin never sees a password), and upsert a local `app_user` row as `INVITED`. Idempotent; Keycloak calls run outside the local transaction, and a retry re-sends the invite whenever the account still has no credentials (a mid-flight failure never strands a credential-less account).
- **Access gate** (`ProvisioningGateFilter`, after JWT auth): an unknown `sub` → `403 account_not_provisioned`; `DISABLED` → 403 **everywhere, `/me` included**; `INVITED` → lazily activated to `ACTIVE` (+`UserActivated`) then allowed; `GET /api/v1/me` (method-scoped) is reachable while INVITED/unprovisioned for onboarding. Unexpected gate errors render the unified envelope (never a bare 500). Config-gated (`app.provisioning.gate-enabled`).
- **`UserDirectory` public port**: resolve a provisioned user's immutable subject by email (used by notification's in-app targeting).
- **Keycloak Admin API** via Spring `RestClient` + a self-managed service-account token (`client_credentials`, cached, refreshed early) — **no** `keycloak-admin-client` (Jackson-2/RESTEasy drag).
- **Impersonation** — the only sanctioned path from a platform tier to tenant data, because the two authorization axes never intersect. `POST`/`GET`/`DELETE /api/v1/admin/impersonations` (floor `platform-support`; `mode=WRITE` needs `platform-admin`), and `X-Impersonate: <sessionId>` on any `/api/**` request. The swapped principal carries **zero** authorities, which is the mechanism both ways: org permissions still resolve for the target so tenant endpoints work, while every `hasRole('platform-*')` fails so `/admin/**` is unreachable from inside a session. Every gate is re-decided per request (never trusted from open time), sessions are time-boxed and reason-bearing, and `audit_log.actor` names the **operator** while the worn identity moves to `on_behalf_of`. `app.impersonation.enabled=false` makes the feature *refuse* (403 naming the switch), not vanish.
- **Reconciliation**: `IdentityReconciliationJob` (daily, ShedLock-guarded) compares `app_user` against Keycloak and disables rows whose account is definitively gone, auditing each as `identity.user_disabled_by_reconciliation`. Built to be wrong safely — a tri-state presence lookup (an inconclusive answer is never a deletion), a grace period that keeps it off mid-provisioning rows, and a `max-orphan-ratio` circuit breaker that treats a mass disappearance as a misconfiguration and changes nothing. Ships in `REPORT` mode.
- **Endpoints**: `GET /api/v1/me` (self + active org + provisioning status), `GET /api/v1/admin/users` (`platform-support`, cursor-paginated), `POST`/`GET`/`DELETE /api/v1/admin/impersonations`.
- Verified: orchestration + no-JIT gate against real Postgres (Keycloak mocked), plus a **live Testcontainers Keycloak IT** that provisions a user and asserts the `execute-actions-email` invite reaches a real **Mailpit** SMTP sink end-to-end.

## organization
Local projection of **Keycloak Organizations** + the **org-scoped RBAC authority**.
- **Model**: permissions are a **fixed enum catalog** (`org:*`, `member:*`, `role:*`); roles are **DB-editable bundles** of permissions. **OWNER** (all permissions) is the only seeded, immutable role and the only code the application names — first-owner bootstrap and last-owner protection; every other org role is one an owner created, and no request path reads its code. `org_id` everywhere is the Keycloak org UUID (the tenant key). Cross-module member links are soft refs (`user_subject`, no FK).
- **Authority**: implements the shared `OrgAuthorization` port → makes `@PreAuthorize("hasPermission(#orgId, 'organization', 'member:invite')")` live. A check passes only when the org is **ACTIVE** (suspension cuts all access), the token's **active org == `#orgId`** by strict id equality (cross-org denied before any DB hit), **and** the caller's role in that org carries the permission. Effective permissions are cached (Caffeine L1 + Valkey L2), **evicted on role/membership/org-status change** via `@ApplicationModuleListener`.
- **Escalation guard everywhere permissions change hands**: role create/update AND member invite/role-assign require the caller to already hold every permission being granted — `member:role:assign` alone can never mint an OWNER. System roles are **reconciled against the permission catalog** at startup (`SystemRoleCatalogReconciler`), so a new enum value reaches existing orgs.
- **Provisioning orchestration**: `POST /orgs` (`platform-admin`) creates the Keycloak org, projects it, seeds roles, and installs the first **OWNER** — strict create: a pre-existing alias (local or Keycloak-side) is a `409`, never a silent adoption. `POST /orgs/{orgId}/members` provisions the identity (Keycloak user + temp creds) and links the membership in one call. **Last-owner protection** on remove/role-reassign; member removal commits locally first, then unlinks in Keycloak outside the transaction (no row locks across HTTP); the Keycloak user account is kept.
- **Endpoints**: `POST /orgs`, `GET/PATCH /orgs/{orgId}`, `POST /orgs/{orgId}/suspend|reactivate` (`platform-admin`), `GET/POST /orgs/{orgId}/members`, `PUT /orgs/{orgId}/members/{subject}/role`, `DELETE /orgs/{orgId}/members/{subject}`, `GET/POST/PUT/DELETE /orgs/{orgId}/roles[/{roleId}]`, `GET /api/v1/permissions`. Writes honor `Idempotency-Key` transparently.
- **Active org** comes from the JWT Keycloak `organization` claim (`addOrganizationId=true`); a token scoped to ≠1 org has no active org → org-scoped checks deny. `scripts/token.sh` requests `scope=organization`.
- Verified: owner/custom-role permission matrix (roles built by the test the way an owner builds them) + proof that a role code grants nothing by itself — a role named `ADMIN` behaves exactly like its permission set — + cross-org & no-active-org denial (HTTP), invite provisioning across modules (Keycloak mocked), last-owner block, unknown-permission `422`, and **async permission-cache eviction** after a role change. A **live Testcontainers Keycloak IT** pins the Organizations Admin-API wire contract (create-org, search-by-alias, add-member, remove-member) end-to-end — it caught two live gateway bugs, both fixed: alias lookup used `exact=true` (Keycloak's `search` matches name/domain, not alias, so it always came back empty), and `addMember` was not idempotent (Keycloak 409s on re-add, which broke re-invite).

## audit
An append-only trail: **who / when / where / what / from→to** for every state change.
- **Recording**: a shared **`AuditLog` port** that mutation services call at the point of change, *inside the changing transaction* — so the audit row commits (or rolls back) atomically with the change and the acting principal is still on the thread. The audit module's impl fills in **who** from the security context (null for system-triggered changes — dev bootstrap, startup reconciliation) and **when** from the clock. A no-op default `AuditLog` lives in `shared` so single-module slice tests boot without the audit module (the real impl is `@Primary`).
- **Instrumented**: organization (org create/rename/suspend/reactivate, member add/remove/role-change, role create/update/delete), identity (user provisioned), settings (setting changed, feature-flag changed) — each capturing the **before/after** state.
- **Query**: `GET /api/v1/audit` (`platform-support`, all orgs) and `GET /api/v1/orgs/{orgId}/audit` (org-scoped, gated by the **`audit:read`** permission — an org's own admins review their trail without platform access). Both cursor-paginated (newest first) and filterable by `action` + a `from`/`to` ISO-instant window.
- Verified: capture through a real service records who + what + from→to atomically (and null actor for system changes); REST filtering, pagination, `platform-support` gating, and org-scoped `audit:read` with cross-org isolation.

## webhooks
Per-org **outbound event subscriptions**: tenants register an endpoint + the events they want, and receive **signed, durably-delivered** POSTs.
- **Subscriptions**: org-scoped CRUD (`webhook:manage` permission). A subscription is a URL + a generated HMAC secret + a set of event codes (`org.member.added`, `org.member.removed`, `org.member.role_changed`, `org.role.permissions_changed`, `org.status_changed`). The secret is returned **once** on create and masked on every later read. The URL is SSRF-guarded at create.
- **Delivery**: an `@ApplicationModuleListener` fans each organization event out to matching active subscriptions and enqueues one durable delivery each (idempotent via `EventInbox`). A background worker claims batches with `SELECT … FOR UPDATE SKIP LOCKED` (joining the subscription for its URL + secret, so the secret never lands in the delivery row), **HMAC-SHA256-signs** the exact JSON body (`X-Webhook-Signature: sha256=…`), POSTs it through the shared **`SafeOutboundUrl`** SSRF guard on **virtual threads** with a whole-exchange timeout, and retries with backoff / dead-letters. Fenced status updates so a re-claimed row can't be corrupted by a stale claimant.
- **Delivery log**: `GET …/webhooks/{id}/deliveries` (cursor-paginated) — per-attempt status, response code, error.
- The SSRF guard was promoted to `shared.http.SafeOutboundUrl` and is now shared by the notification webhook/Slack channels and webhooks (one security control, not two). It blocks loopback/private/link-local/CGNAT/ULA/NAT64-embedded/special-purpose targets at create **and** send. **Residual**: it can't close DNS-rebinding on its own (the client re-resolves at connect), so production must also enforce an **egress network policy** denying the workload's egress to link-local/metadata/RFC-1918 — the guard is the first line, not the only one.
- Verified: subscription REST + validation + org-scoped authz; the fan-out enqueues on a real published event (Modulith `Scenario`); the worker delivers a **signature-verified** payload to a real receiver and **retries a 5xx to dead-letter** across the attempt budget.
