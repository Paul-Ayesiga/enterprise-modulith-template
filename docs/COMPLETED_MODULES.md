# Completed Modules

What is **built, tested, and gated** today. Backlog/not-yet-built work lives in
[NEXT_TASKS.md](NEXT_TASKS.md); this file lists only finished modules. Every module owns its data,
talks to others through events, and is boundary-verified by Spring Modulith (`./gradlew test`).

_Last updated: 2026-07-28._

## Summary

| Module | Purpose | REST endpoints | Publishes events | Status |
|---|---|---|---|---|
| **shared** (kernel) | Cross-cutting foundation reused by every module | envelope/error/probes | — | ✅ |
| **settings** | System settings + feature flags | `/api/v1/settings`, `/api/v1/feature-flags` | `SettingChanged`, `FeatureFlagChanged` | ✅ |
| **files** | S3-compatible object storage | — (provider API) | — | ✅ |
| **scheduler** | Clustered scheduled jobs | — | — | ✅ |
| **analytics** | Embedded OLAP / reporting | — (query API) | — | ✅ |
| **notification** | Multi-channel, pluggable delivery | `/api/v1/notifications` | — (consumes `FeatureFlagChanged`) | ✅ |
| **identity** | Admin-driven user provisioning (no JIT) | `/api/v1/me`, `/api/v1/admin/users` | `UserProvisioned`, `UserActivated` | ✅ |
| **organization** | Keycloak orgs + org-scoped RBAC authority | `/api/v1/orgs/**`, `/api/v1/permissions` | `OrganizationRegistered`, `MembershipCreated`, `MembershipRoleChanged`, `MemberRemoved`, `RolePermissionsChanged` | ✅ |

---

## shared (kernel)
The reusable enterprise foundation — not a business module, but the substrate they all build on.
- **Web contract**: unified JSON:API-style envelope (`{data|errors, meta, links}`), `meta.requestId` on every response + `X-Request-Id` header, cursor pagination (`page[size]`/`page[after]`).
- **Errors**: `ErrorCode` registry + `ApiException` hierarchy, global handler, **no stack traces on the wire**, RFC 9457 (`application/problem+json`) via content negotiation.
- **Security**: OAuth2 Resource Server validating Keycloak JWTs **including the `smsone-api` audience** (foreign-client/service-account tokens are rejected); realm roles → `ROLE_<role>`, client roles namespaced → `ROLE_<client>_<role>` (a client role named `ADMIN` can never satisfy the platform-admin gate); `@EnableMethodSecurity`, `CurrentUser` + `PermissionEvaluator` seam.
- **Persistence**: `BaseEntity`/`AggregateRoot`, UUID keys, JPA auditing, soft-delete, keyset cursors — real Postgres 18 only (no H2).
- **Observability**: Actuator liveness/readiness probes, OTLP export, structured JSON logs carrying `requestId` + `traceId`, virtual threads.
- **Caching**: two-level Caffeine (L1) + Valkey (L2) with cross-instance invalidation and graceful L2-outage degradation.
- **Reliability**: idempotency-key store, event **outbox** (Modulith registry) + **inbox** (`EventInbox`), Resilience4j circuit breaker.
- **Rate limiting**: distributed token-bucket (Bucket4j over Valkey). Edge filter on `/api/**` — per-route tiers keyed **per-tenant → principal → IP**, `429` with the unified envelope + `Retry-After` + `RateLimit`/`RateLimit-Policy` (draft-ietf) + legacy `X-RateLimit-*`, **fail-open** on backend outage. The same `DistributedRateLimiter` powers the notification **egress** per-channel provider limits.

## settings
System-wide configuration and feature flags (feature flags replaced Togglz — no Boot 4 build).
- **Endpoints**: `GET/PUT /api/v1/settings[/{key}]`, `GET/PUT /api/v1/feature-flags[/{key}]` (writes require `ADMIN`; lists are cursor-paginated).
- **Events**: `SettingChanged(key, value)`, `FeatureFlagChanged(key, enabled)` — published via the DB-backed registry.
- Hot-path `isEnabled(key)` is cached; unknown flags are OFF, never an error.

## files
Object storage behind a single S3 abstraction (AWS SDK v2).
- **API**: `FileStorageProvider` — put/get/delete/exists, presigned GET/PUT, multipart.
- One code path drives local **SeaweedFS**, self-hosted, or managed **S3/R2/B2** (only endpoint + creds change). No REST surface yet — a consuming feature will define it.
- Verified against real SeaweedFS 4.40 (put/get/delete/presign/11 MB multipart).

## scheduler
Scheduled jobs that fire exactly once across all instances.
- `@Scheduled` + **ShedLock** (JDBC provider, `usingDbTime`).
- Ships the event-publication-registry purge and idempotency-key purge jobs.

## analytics
Embedded OLAP for dashboards/KPIs/reports, in-process (no server).
- **Engine**: DuckDB 1.5.5.0 behind an `AnalyticsEngine` seam; thread/memory caps; native Parquet snapshots.
- Marts materialized from Postgres with exact `DECIMAL` money fidelity and UTC-pinned day buckets; atomic staging swap (a failed refresh leaves the old mart intact).

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
- **Endpoints**: `GET /api/v1/me` (self + active org + provisioning status), `GET /api/v1/admin/users` (platform `ADMIN`, cursor-paginated).

## organization
Local projection of **Keycloak Organizations** + the **org-scoped RBAC authority**.
- **Model**: permissions are a **fixed enum catalog** (`org:*`, `member:*`, `role:*`); roles are **DB-editable bundles** of permissions with seeded, immutable **OWNER/ADMIN/MEMBER** (OWNER=all, ADMIN=all-but-`org:delete`, MEMBER=read-only). `org_id` everywhere is the Keycloak org UUID (the tenant key). Cross-module member links are soft refs (`user_subject`, no FK).
- **Authority**: implements the shared `OrgAuthorization` port → makes `@PreAuthorize("hasPermission(#orgId, 'organization', 'member:invite')")` live. A check passes only when the org is **ACTIVE** (suspension cuts all access), the token's **active org == `#orgId`** by strict id equality (cross-org denied before any DB hit), **and** the caller's role in that org carries the permission. Effective permissions are cached (Caffeine L1 + Valkey L2), **evicted on role/membership/org-status change** via `@ApplicationModuleListener`.
- **Escalation guard everywhere permissions change hands**: role create/update AND member invite/role-assign require the caller to already hold every permission being granted — `member:role:assign` alone can never mint an OWNER. System roles are **reconciled against the permission catalog** at startup (`SystemRoleCatalogReconciler`), so a new enum value reaches existing orgs.
- **Provisioning orchestration**: `POST /orgs` (platform `ADMIN`) creates the Keycloak org, projects it, seeds roles, and installs the first **OWNER** — strict create: a pre-existing alias (local or Keycloak-side) is a `409`, never a silent adoption. `POST /orgs/{orgId}/members` provisions the identity (Keycloak user + temp creds) and links the membership in one call. **Last-owner protection** on remove/role-reassign; member removal commits locally first, then unlinks in Keycloak outside the transaction (no row locks across HTTP); the Keycloak user account is kept.
- **Endpoints**: `POST /orgs`, `GET/PATCH /orgs/{orgId}`, `POST /orgs/{orgId}/suspend|reactivate` (platform `ADMIN`), `GET/POST /orgs/{orgId}/members`, `PUT /orgs/{orgId}/members/{subject}/role`, `DELETE /orgs/{orgId}/members/{subject}`, `GET/POST/PUT/DELETE /orgs/{orgId}/roles[/{roleId}]`, `GET /api/v1/permissions`. Writes honor `Idempotency-Key` transparently.
- **Active org** comes from the JWT Keycloak `organization` claim (`addOrganizationId=true`); a token scoped to ≠1 org has no active org → org-scoped checks deny. `scripts/token.sh` requests `scope=organization`.
- Verified: OWNER/ADMIN/MEMBER matrix + cross-org & no-active-org denial (HTTP), invite provisioning across modules (Keycloak mocked), last-owner block, unknown-permission `422`, and **async permission-cache eviction** after a role change.
