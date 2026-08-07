# Completed Modules

What is **built, tested, and gated** today; this file lists only finished modules. Every module owns its data,
talks to others through events, and is boundary-verified by Spring Modulith (`./gradlew test`).

_Last updated: 2026-08-07 (the identity decoupling). What is written below is accurate; the list is not
yet complete — eleven modules (access, apikeys, compliance, geo, integration, maintenance, mcp,
payments, profile, signup, support) are shipped and gated, and their gates are in
[CHECKLIST.md](CHECKLIST.md), but they have no write-up here yet._

## Summary

| Module | Purpose | REST endpoints | Publishes events | Status |
|---|---|---|---|---|
| **shared** (kernel) | Cross-cutting foundation reused by every module | envelope/error/probes | — | ✅ |
| **settings** | System settings + feature flags | `/api/v1/settings`, `/api/v1/feature-flags` | `SettingChanged`, `FeatureFlagChanged` | ✅ |
| **localization** | Translation catalog + `Messages` resolution port | `/api/v1/translations/**` | `TranslationChanged` | ✅ |
| **search** | Postgres FTS projection + `SearchIndex` port | `/api/v1/orgs/{orgId}/search`, `/api/v1/admin/search` | — (consumes `OrganizationRegistered`, `PersonProvisioned`) | ✅ |
| **document** | Managed-file catalog over the files port + `Documents` port | `/api/v1/orgs/{orgId}/documents/**`, `/api/v1/documents/**` | `DocumentRegistered` | ✅ |
| **exchange** | Data Exchange Platform — durable, resumable import/export jobs behind the `ExchangeHandler` SPI (CSV/JSONL/XLSX/XML + ZIP, templates, recurring schedules) | `/api/v1/orgs/{orgId}/exchange/**`, `/api/v1/exchange/handlers/**` | — (drives domain services through the SPI) | ✅ |
| **subscription** | Plan catalog + per-org subscription + the `Entitlements` gating port | `/api/v1/admin/orgs/{id}/subscription`, `/api/v1/admin/plans`, `/api/v1/orgs/{orgId}/subscription` | `SubscriptionChanged` | ✅ |
| **billing** | Kill Bill integration — accounts, billable plans, invoices, payment-state reconciliation into entitlements | `/api/v1/admin/orgs/{id}/billing/**`, `/api/v1/orgs/{orgId}/billing/invoices` | — (drives the `Subscriptions` port) | ✅ |
| **files** | S3-compatible object storage | `/api/v1/files` | — | ✅ |
| **scheduler** | Clustered scheduled jobs | `/api/v1/scheduler/locks` | — | ✅ |
| **analytics** | Embedded OLAP / reporting | `/api/v1/analytics/reports` | — | ✅ |
| **notification** | Multi-channel, pluggable delivery | `/api/v1/notifications` | — (consumes `FeatureFlagChanged`) | ✅ |
| **identity** | `person` — the canonical human — plus admin-driven provisioning (no JIT), provider links and audited impersonation | `/api/v1/me`, `/api/v1/admin/users`, `/api/v1/admin/impersonations` | `PersonProvisioned`, `PersonActivated` | ✅ |
| **organization** | Tenants (`organization.id` is the tenant key) + org-scoped RBAC authority | `/api/v1/orgs/**`, `/api/v1/permissions` | `OrganizationRegistered`, `MembershipCreated`, `MembershipRoleChanged`, `MemberRemoved`, `RolePermissionsChanged`, `OrganizationStatusChanged` | ✅ |
| **audit** | Append-only audit trail (who/when/where/what/from→to) | `/api/v1/audit`, `/api/v1/orgs/{orgId}/audit` | — (records via the `AuditLog` port) | ✅ |
| **webhooks** | Per-org outbound event subscriptions (signed, durable) | `/api/v1/orgs/{orgId}/webhooks/**` | — (consumes org events) | ✅ |

---

## shared (kernel)
The reusable enterprise foundation — not a business module, but the substrate they all build on.
- **Web contract**: unified JSON:API-style envelope (`{data|errors, meta, links}`), `meta.requestId` on every response + `X-Request-Id` header, cursor pagination (`page[size]`/`page[after]`).
- **Errors**: `ErrorCode` registry + `ApiException` hierarchy, global handler, **no stack traces on the wire**, RFC 9457 (`application/problem+json`) via content negotiation.
- **Security**: OAuth2 Resource Server validating Keycloak JWTs **including the `smsone-api` audience** (foreign-client/service-account tokens are rejected); realm roles → `ROLE_<role>`, client roles namespaced → `ROLE_<client>_<role>` (a client role named `platform-admin` can never satisfy the realm-role gate); `@EnableMethodSecurity`, `CurrentUser` + `PermissionEvaluator` seam.
- **The edge translates, once**: a validated token's `iss`/`sub` pair becomes a `person.id` through the `PersonLookup` port and its `organization` claim becomes an `organization.id` through `OrgLookup` — both implemented by the modules that own the link tables, so `shared` still compile-depends on no business module. `CurrentUser` therefore carries **no subject and no provider org id**: below the edge the platform speaks only its own identifiers, which is what makes adding or swapping an identity provider a row in `external_identity` rather than a change in every module that needs to know who is calling. Neither port default-grants — with no implementation present a token resolves to no person and no active org, so every check denies.
- **Persistence**: `BaseEntity`/`AggregateRoot`, UUID keys, JPA auditing, soft-delete, keyset cursors — real Postgres 18 only (no H2). `created_by`/`updated_by` are `person.id` uuids with **no foreign key**, deliberately: this modulith is built to split into services, and a real FK would make identity's table a hard dependency of every other module's schema. NULL means a system job or a machine credential — a uuid column has no room for the old `system` sentinel string, and inventing a synthetic person to hold it would put a row in `person` that is eligible for memberships, audit attribution and erasure.
- **Observability**: Actuator liveness/readiness probes, OTLP export, structured JSON logs carrying `requestId` + `traceId` + `org_id` (tenant-scoped requests and exchange job runs), virtual threads. Custom Micrometer counters at every give-up/refusal point — dead-letters (both queues), 429s by tier, exchange jobs/records, cache hit/miss, impersonation lifecycle, purge deletions (catalogue: SRS §5.6) — with two Grafana dashboards file-provisioned into the dev otel-lgtm (`docker/grafana/`).
- **Caching**: two-level Caffeine (L1) + Valkey (L2) with cross-instance invalidation and graceful L2-outage degradation.
- **Reliability**: idempotency-key store, event **outbox** (Modulith registry) + **inbox** (`EventInbox`), Resilience4j circuit breaker.
- **Rate limiting**: distributed token-bucket (Bucket4j over Valkey). Edge filter on `/api/**` — per-route tiers keyed **per active-org (the resolved `organization.id`) → flat tenant-claim → principal (`person:<uuid>` or `key:<uuid>`) → IP**, `429` with the unified envelope + `Retry-After` + `RateLimit`/`RateLimit-Policy` (draft-ietf) + legacy `X-RateLimit-*`, **fail-open** on backend outage. The same `DistributedRateLimiter` powers the notification **egress** per-channel provider limits.

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
- Soft-deletable (`V21`), eighth of twelve in `PURGE_ORDER`; deletes publish `TranslationChanged`
  explicitly.

## search
Lightning-fast full-text search on Postgres — no new engine, and the speed is a measured gate.
- **Projection**: `search_document` with a GENERATED `tsvector` (GIN) + trigram GIN over titles;
  rebuildable by design, so it is not soft-deletable and has no purge job — producers own removal.
- **Feeding**: the `SearchIndex` port (idempotent upsert per `(entity_type, entity_id)`) plus
  inbox-guarded listeners on `OrganizationRegistered` (org-scoped) and `PersonProvisioned`
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
- **Catalog**: name, `owner_person_id`, org (null = personal), provenance (`UPLOAD` | `EXCHANGE` via the
  `Documents` port — the exchange platform files its artifacts here). Soft-deletable, ninth in
  `PURGE_ORDER`.
- **Scoping**: org documents behind the additive `document:read`/`document:manage` pair (reconciled
  onto existing OWNERs at startup); personal documents tier by blast radius like files —
  support reads across people, admin deletes.
- **Delete, asymmetric by design**: the object goes immediately (remote, outside the tx), the row
  soft-remains with its audit trail — a restore recovers the record, never the content.
- **Search tie-in**: titles registered/un-indexed through the `SearchIndex` port — the port's
  reference producer.
- **Endpoints**: org `POST/GET` list, `GET /{id}` (302 presigned), `DELETE /{id}`; personal
  equivalents under `/api/v1/documents`.

## exchange
The Data Exchange Platform ([the guidelines doc](plans/reusable-data-exchange-platform-guidelines.md) is
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
  REQUESTER'S permissions at processing time (`exchange_job.requester_person_id`, resolved through
  `PermissionEscalationGuard.requirePersonHolds`) — revocation between submit and run bites.
- **Formats are additive**: streaming `FormatCodec` SPI; CSV (commons-csv, RFC-4180) and JSON Lines
  ship; a new format touches no business logic.
- **Artifacts are documents**: source, error report and export result all register through the
  `Documents` port as `EXCHANGE` provenance — tenants browse their exchange history as documents.
- **Terminal is an event**: `JobCompleted` (published from the fenced terminal row, so it can never
  disagree with the API) tells the requester in-app and fans out `org.exchange.job_completed`;
  `ExchangeRetentionJob` purges terminal jobs past `app.exchange.retention` nightly (artifacts
  keep the document lifecycle).
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
- **Lifecycle doc**: [TENANT_LIFECYCLE.md](plans/TENANT_LIFECYCLE.md) — states, transitions, and the
  platform surface (`/api/v1/admin/orgs/**`) that drives them, including SUSPENDED-only delete.
- Billing integrates through the `Subscriptions` write port — see the billing module below.

## billing
Payments and invoicing through **Kill Bill**, the billing system of record — nothing financial is
stored locally except the account linkage.
- **Linkage**: one KB account per org (`externalKey` = org id, idempotent provisioning that
  survives create races), recorded in `billing_account` (soft-deletable projection) and audited.
- **One write direction**: KB subscription state and payment outcomes reconcile INTO the
  subscription module through the `Subscriptions` port — a paid plan lands on the SAME audited
  assign path as a manual comp; `INVOICE_PAYMENT_FAILED` → `PAST_DUE` (grace, entitlements kept),
  recovery → `ACTIVE`, no billable subscription → FREE.
- **Callback**: Kill Bill push notifications hit a token-authenticated, `@Hidden` machine endpoint
  (constant-time compare, 401 before any read; unknown events 200-acknowledged; transient failures
  5xx so KB retries).
- **Surfaces**: platform provisions/inspects/subscribes/invoices under
  `/api/v1/admin/orgs/{id}/billing/**`; the tenant reads its own invoices. Kaui is the back office.
- **Dev loop**: `docker compose` ships killbill + mariadb + kaui; `BILLING_BOOTSTRAP=true` creates
  the KB tenant + simple catalog plans (PRO/ENTERPRISE) so the stack bills out of the box.
- **Verified** against a REAL Kill Bill container (tenant, plans, accounts, subscription →
  ACTIVE, invoices) plus mocked-gateway flows for reconcile/payment/callback/authz.

## files
Object storage behind a single S3 abstraction (AWS SDK v2).
- **API**: `FileStorageProvider` — put/get/delete/exists, presigned GET/PUT, multipart.
- One code path drives local **SeaweedFS**, self-hosted, or managed **S3/R2/B2** (only endpoint + creds change).
- **Endpoints**: `POST /api/v1/files` (multipart upload), `GET`/`DELETE /api/v1/files/{key}`, `POST /api/v1/files/presign`. Keys are namespaced per caller (`u/<person-id>/…` — a machine key has no person id and is refused the personal namespace rather than given one); read/delete/presign are owner-scoped, with cross-namespace access tiered by blast radius (`platform-support` reads, `platform-admin` deletes). Download 302s to a short-lived presigned URL, so bytes stream straight from storage (correct content-type, no app proxying).
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
- **Channels** (each a `NotificationChannelSender` bean — add one to extend): **Email** (SMTP/Mailpit), **In-app** (persisted per `person.id`, read via REST), **Webhook** (HTTP POST with an **SSRF guard** — private/loopback hosts refused unless `webhook-allow-private-hosts`), **Slack** (incoming-webhook POST), **SMS** (dev stub — drop in Africa's Talking/Twilio and set `app.notification.sms.stub=false`). HTTP sends carry a whole-exchange deadline (a hung receiver can't stall the pipeline); `4xx` responses dead-letter immediately (permanent), `5xx/408/429` retry with backoff.
- **Public API**: `Notifications` facade (`dispatch(NotificationRequest)`, `notifyAdmins`) with per-recipient channel addressing (`Recipient`).
- **Endpoints**: `GET /api/v1/notifications` (current user's in-app, cursor-paginated), `POST /api/v1/notifications/{id}/read`.
- **Who a notification is for, twice corrected**: it was `preferred_username` (mutable and recyclable — a freed name inherited the previous holder's notifications), then the Keycloak subject, and it is `in_app_notification.person_id` now. The subject was still one provider's name for someone: two credentials for the same human addressed two different inboxes, and a realm rebuild orphaned the lot. A person id survives both. The column is a soft ref with no FK and the table deliberately carries **no** `org_id` — a notification belongs to the person, not to the tenant they were acting in, which is why per-org retention has no meaning here.
- **Event-driven**: `@ApplicationModuleListener` on `FeatureFlagChanged` notifies admins (email + in-app), idempotent via `EventInbox`.
- **Async, scalable fan-out**: `dispatch` is non-blocking — it enqueues one row per recipient/channel into `notification_delivery`, then a background worker claims batches with `SELECT … FOR UPDATE SKIP LOCKED` and sends on a bounded pool of **virtual threads** (Java 21), **outside any DB transaction**, with exponential-backoff **retry**, **dead-lettering**, and stale-lock recovery. SKIP LOCKED lets N instances share the queue with no double-sends → fans out to thousands without blocking the caller. One channel/recipient failing never aborts the rest.
- **Per-channel egress limits**: before a send, a cluster-wide (Valkey) token bucket per channel throttles downstream providers (SMS/SMTP/…) — throttled deliveries are deferred, not failed (attempt not burned). Configure `app.notification.delivery.rate.<CHANNEL>`.
- Verified against real Mailpit (email), a real webhook receiver (fan-out), a **300-recipient concurrency test** (each hit exactly once, no duplicates), and a **per-channel throttle test** (2 of 5 sent, 3 deferred).

## identity
The canonical identity of a human on this platform + **admin-driven provisioning — no JIT** (a valid JWT is *not* access).
- **`person` is the identity; a provider account is a link to it.** `person.id` is what every other module stores, and `external_identity` maps `(provider, issuer, external_subject) → person.id`. Keycloak is ONE row shape in that table, not its reason to exist: the platform can add or swap an identity provider by inserting rows here, without touching another module. This replaced `app_user`, which welded two things together — the local access record and one provider's `sub` — and leaked the second everywhere, because every reader keyed on the subject string rather than on a row id of ours. `person_contact` holds the e-mail (`kind=EMAIL`, `is_primary`, `verified_at`); it left the person row because an address is something a person *has*, several of, each separately proven.
- **Lifecycle**: `status` INVITED | ACTIVE | DISABLED with `invited_at` / `activated_at` / `disabled_at`. `invited_at` was `provisioned_at`: "provisioned" named what the administrator did, and the thing worth timing is when the human was *asked in*, against which `activated_at` — when they first turned up — is the useful interval.
- **Provisioning** (`PersonProvisioning` public port): create the `person` **first**, record the e-mail in `person_contact`, then create-or-reuse the Keycloak account, record its subject in `external_identity`, and issue **temporary credentials** (`execute-actions-email` — the admin never sees a password). The order is the fix: provisioning used to call Keycloak first and adopt whatever id came back as the identity of the human, which made a second identity provider a migration rather than an insert. Idempotent (`ProvisionedPerson.alreadyExisted` is true only when the person already had a live Keycloak link, so a person row with no link is completed rather than skipped); Keycloak calls run outside the local transaction, and a retry re-sends the invite whenever the account still has no credentials (a mid-flight failure never strands a credential-less account). Publishes `PersonProvisioned`, then `PersonActivated` on first arrival.
- **The translation seam** (`PersonResolver`, plus the kernel's `PersonLookup` / `OrgLookup` ports): the one class where a provider subject becomes a `person.id`. Confining it here is what makes "nothing below the edge sees a Keycloak subject" checkable rather than aspirational. The issuer is read from the resource-server property so `external_identity.issuer` is byte-identical to the `iss` the edge accepted — a second key would drift silently and 403 every human at once. Cached, because it is the platform's hottest read — once per authenticated request, for a row that cannot change while it lives. `ProviderOrgMembership` is the reverse direction, attaching a person at the provider's own organization so its tokens carry the `organization` claim.
- **Access gate** (`ProvisioningGateFilter`, after JWT auth): a token whose issuer/subject resolves to no person → `403 account_not_provisioned`; `DISABLED` → 403 **everywhere, `/me` included**; `INVITED` → lazily activated to `ACTIVE` (+`PersonActivated`) then allowed; `GET /api/v1/me` (method-scoped) is reachable while INVITED/unprovisioned for onboarding. A **machine credential short-circuits the gate**: no-JIT asks a question about humans, an API key's admission is the act of minting it, and `ApiPermissionEvaluator` bounds what it reaches. Under impersonation the gate evaluates the TARGET and *peeks* rather than activates — an operator reading someone's data is not that person showing up. Unexpected gate errors render the unified envelope (never a bare 500). Config-gated (`app.provisioning.gate-enabled`).
- **`PersonDirectory` public port** (replaces `UserDirectory`): person id by e-mail (a VERIFIED address outranks an unverified one holding the same value — the only ordering the partial unique index can guarantee), batched person id → primary e-mail for windowed readers, and read-only `linkedAccounts`. That last one used to be an HTTP call to Keycloak on every render; the links are rows now, so it is a local select, and it keeps answering for providers Keycloak is not.
- **Keycloak Admin API** via Spring `RestClient` + a self-managed service-account token (`client_credentials`, cached, refreshed early) — **no** `keycloak-admin-client` (Jackson-2/RESTEasy drag). Its `firstName`/`lastName` vocabulary stops at the gateway class; the platform stores `given_name`/`family_name`, because name ORDER is cultural and "first" names a position rather than a part.
- **Impersonation** — the only sanctioned path from a platform tier to tenant data, because the two authorization axes never intersect. `POST`/`GET`/`DELETE /api/v1/admin/impersonations` (floor `platform-support`; `mode=WRITE` needs `platform-admin`), and `X-Impersonate: <sessionId>` on any `/api/**` request. The swapped principal carries **zero** authorities, which is the mechanism both ways: org permissions still resolve for the target so tenant endpoints work, while every `hasRole('platform-*')` fails so `/admin/**` is unreachable from inside a session. Every gate is re-decided per request (never trusted from open time), sessions are time-boxed and reason-bearing, and `audit_log.actor_person_id` names the **operator** while the worn identity moves to `on_behalf_of_person_id`. The session names people by `person.id` end to end (`targetPersonId` on the wire); `target_display` is copied at open time and never refreshed, so the trail stays readable after the account is gone. `app.impersonation.enabled=false` makes the feature *refuse* (403 naming the switch), not vanish.
- **Reconciliation**: `IdentityReconciliationJob` (daily, ShedLock-guarded) compares each person's Keycloak link against Keycloak and disables those whose account is definitively gone, auditing each as `identity.user_disabled_by_reconciliation`. It corrects the RECORD, it does not close a hole — provisioning is no-JIT and authentication is Keycloak's, so a deleted account can never mint a token whatever these tables say. Built to be wrong safely: a tri-state presence lookup (an inconclusive answer is never a deletion), a grace period that keeps it off mid-provisioning rows, a `max-orphan-ratio` circuit breaker that treats a mass disappearance as a misconfiguration and changes nothing, and a run deadline well inside the ShedLock lease. A person with no link at all is counted and left alone — a future second provider is not an orphan. Ships in `REPORT` mode.
- **Endpoints**: `GET /api/v1/me` (person id, e-mail from `person_contact` rather than from the token, roles, active org, provisioning status), `GET /api/v1/admin/users` (`platform-support`, cursor-paginated — the id is `person.id` and `subject` is gone, because a Keycloak subject was never something a support operator could act on), `POST`/`GET`/`DELETE /api/v1/admin/impersonations`.
- Verified: provisioning orchestration + the no-JIT gate against real Postgres (Keycloak mocked) — `IdentityProvisioningTest`; plus a **live Testcontainers Keycloak IT** that provisions a person, asserts the `execute-actions-email` invite reaches a real **Mailpit** SMTP sink end to end, and pins the baseline realm role it grants (`KeycloakProvisioningIntegrationTest`).

## organization
The tenant itself + the **org-scoped RBAC authority**, with the identity provider's own org as a link beside it.
- **Model**: permissions are a **fixed enum catalog** (`org:*`, `member:*`, `role:*`); roles are **DB-editable bundles** of permissions. **OWNER** (all permissions) is the only seeded, immutable role and the only code the application names — first-owner bootstrap and last-owner protection; every other org role is one an owner created, and no request path reads its code. **`organization.id` is the tenant key**, on the wire and in every table; the provider's org id is one row in `external_organization`, the org-side twin of `external_identity`. It used to be the other way round — `kc_org_id` WAS the tenant key — and that identifier escaped its own module into every `org_id` column, the public API, Kill Bill and the gateway, which is precisely the reach that made a second provider impossible. Cross-module member links are soft refs (`membership.person_id`, no FK).
- **Authority**: implements the shared `OrgAuthorization` port → makes `@PreAuthorize("hasPermission(#orgId, 'organization', 'member:invite')")` live. A check passes only when the org is **ACTIVE** (suspension cuts all access), the token's **active org == `#orgId`** by strict id equality (cross-org denied before any DB hit), **and** the caller's role in that org carries the permission. Effective permissions are cached (Caffeine L1 + Valkey L2), **evicted on role/membership/org-status change** via `@ApplicationModuleListener`.
- **Escalation guard everywhere permissions change hands**: role create/update AND member invite/role-assign require the caller to already hold every permission being granted — `member:role:assign` alone can never mint an OWNER. System roles are **reconciled against the permission catalog** at startup (`SystemRoleCatalogReconciler`), so a new enum value reaches existing orgs.
- **Provisioning orchestration**: `POST /orgs` (`platform-admin`) creates the tenant row, creates the Keycloak org and records the link, seeds roles, and installs the first **OWNER** — strict create: a pre-existing alias (local or Keycloak-side) is a `409`, never a silent adoption. `POST /orgs/{orgId}/members` provisions the person (Keycloak account + temp creds through `PersonProvisioning`), attaches them at the provider via `ProviderOrgMembership`, and links the membership in one call — the Keycloak *user* id is never seen here, because only identity may hold an identifier minted elsewhere. **Last-owner protection** on remove/role-reassign; member removal commits locally first, then detaches at the provider outside the transaction (no row locks across HTTP); the person's account is kept.
- **Endpoints**: `POST /orgs`, `GET/PATCH /orgs/{orgId}`, `POST /orgs/{orgId}/suspend|reactivate` (`platform-admin`), `GET/POST /orgs/{orgId}/members`, `PUT /orgs/{orgId}/members/{personId}/role`, `DELETE /orgs/{orgId}/members/{personId}`, `GET/POST/PUT/DELETE /orgs/{orgId}/roles[/{roleId}]`, `GET /api/v1/permissions`. Writes honor `Idempotency-Key` transparently.
- **Active org**: the JWT's Keycloak `organization` claim (`addOrganizationId=true`) is resolved through `external_organization` — via the shared `OrgLookup` port — into an `organization.id` at the edge, so nothing downstream ever handles a provider org id. The claim is a map keyed by ALIAS and either half can resolve it, which is why both are indexed; an alias is never trusted as an organization id, or a token scoped to one tenant could address another whose local slug happens to collide. A token scoped to ≠1 org has no active org → org-scoped checks deny. `scripts/token.sh` requests `scope=organization`.
- Verified: owner/custom-role permission matrix (roles built by the test the way an owner builds them) + proof that a role code grants nothing by itself — a role named `ADMIN` behaves exactly like its permission set — + cross-org & no-active-org denial (HTTP), invite provisioning across modules (Keycloak mocked), last-owner block, unknown-permission `422`, and **async permission-cache eviction** after a role change. A **live Testcontainers Keycloak IT** pins the Organizations Admin-API wire contract (create-org, search-by-alias, add-member, remove-member) end-to-end — it caught two live gateway bugs, both fixed: alias lookup used `exact=true` (Keycloak's `search` matches name/domain, not alias, so it always came back empty), and `addMember` was not idempotent (Keycloak 409s on re-add, which broke re-invite).

## audit
An append-only trail: **who / when / where / what / from→to** for every state change.
- **Recording**: a shared **`AuditLog` port** that mutation services call at the point of change, *inside the changing transaction* — so the audit row commits (or rolls back) atomically with the change and the acting principal is still on the thread. The audit module's impl fills in **who** — `actor_person_id`, from `CurrentUser.accountablePersonId()` — and **when** from the clock. NULL there is a fact, not a gap: a system job (dev bootstrap, startup reconciliation) and a machine key both have no accountable human, and a uuid column has nowhere to put the old `system` sentinel string. A no-op default `AuditLog` lives in `shared` so single-module slice tests boot without the audit module (the real impl is `@Primary`).
- **Instrumented**: organization (org create/rename/suspend/reactivate, member add/remove/role-change, role create/update/delete), identity (person provisioned), settings (setting changed, feature-flag changed) — each capturing the **before/after** state.
- **Query**: `GET /api/v1/audit` (`platform-support`, all orgs) and `GET /api/v1/orgs/{orgId}/audit` (org-scoped, gated by the **`audit:read`** permission — an org's own admins review their trail without platform access). Both cursor-paginated (newest first) and filterable by `action` + a `from`/`to` ISO-instant window.
- Verified: capture through a real service records who + what + from→to atomically (and a null `actor_person_id` for system changes); REST filtering, pagination, `platform-support` gating, and org-scoped `audit:read` with cross-org isolation.

## webhooks
Per-org **outbound event subscriptions**: tenants register an endpoint + the events they want, and receive **signed, durably-delivered** POSTs.
- **Subscriptions**: org-scoped CRUD (`webhook:manage` permission). A subscription is a URL + a generated HMAC secret + a set of event codes from the on-wire vocabulary (`GET /api/v1/webhooks/event-types` — org member/role/status/deletion events, `org.subscription_changed`, `org.exchange.job_completed`). The secret is returned **once** on create, masked on every later read, and **AES-256-GCM encrypted at rest** (`SecretCipher`, key from `app.webhooks.secret-encryption-key`; a startup migrator rewrote pre-encryption rows; the sender decrypts only to sign — encryption, not hashing, because HMAC needs the plaintext). The URL is SSRF-guarded at create.
- **Delivery**: an `@ApplicationModuleListener` fans each organization event out to matching active subscriptions and enqueues one durable delivery each (idempotent via `EventInbox`). A background worker claims batches with `SELECT … FOR UPDATE SKIP LOCKED` (joining the subscription for its URL + secret, so the secret never lands in the delivery row), **HMAC-SHA256-signs** the exact JSON body (`X-Webhook-Signature: sha256=…`), POSTs it through the shared **`SafeOutboundUrl`** SSRF guard on **virtual threads** with a whole-exchange timeout, and retries with backoff / dead-letters. Fenced status updates so a re-claimed row can't be corrupted by a stale claimant.
- **Delivery log**: `GET …/webhooks/{id}/deliveries` (cursor-paginated) — per-attempt status, response code, error.
- The SSRF guard was promoted to `shared.http.SafeOutboundUrl` and is now shared by the notification webhook/Slack channels and webhooks (one security control, not two). It blocks loopback/private/link-local/CGNAT/ULA/NAT64-embedded/special-purpose targets at create **and** send. **Residual**: it can't close DNS-rebinding on its own (the client re-resolves at connect), so production must also enforce an **egress network policy** denying the workload's egress to link-local/metadata/RFC-1918 — the guard is the first line, not the only one.
- Verified: subscription REST + validation + org-scoped authz; the fan-out enqueues on a real published event (Modulith `Scenario`); the worker delivers a **signature-verified** payload to a real receiver and **retries a 5xx to dead-letter** across the attempt budget.
