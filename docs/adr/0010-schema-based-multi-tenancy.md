# ADR 0010 — Schema-based multi-tenancy: pooled-plus-siloed, with a promotion path

- **Status:** Proposed · **Date:** 2026-08-08 · **Supersedes nothing** · **Next free migration: V53**
- **Companion plan:** the phased delivery is §7 of this document. Tick `docs/CHECKLIST.md` as each gate passes.
- Written against the live database (Postgres 18.4, 52 migrations, **55 tables**, 5,000 orgs, 1.8 GB) and the source at `/Users/ayesigapaul/IdeaProjects/enterprise-modulith-template`. Every number below was measured, not inferred.

---

## 1. The decision

**We adopt pooled-plus-siloed schema tenancy: all tenants live in one shared schema `tenant_pool` by default, any tenant can be promoted to its own schema `t_<32hex>`, and both are the same code path with a different `search_path` string.** Platform-shared tables move into a `platform` schema; extensions move into `ext`; `public` is emptied.

**The reason is extractability, not isolation.** Schema separation inside one Postgres cluster buys almost nothing in security terms — the same role reaches every schema — and the codebase's real isolation guarantees already live in `ApiPermissionEvaluator`, `OrgAuthorization`, and the impersonation rules. What it buys is that `pg_dump -n t_<hex>` is a complete, restorable tenant with no `WHERE org_id = ?` predicate to get wrong and no dependency-ordered extraction script to forget a table in. That property is what makes "lift org X onto its own database" a runbook step instead of a project.

**Uniform schema-per-tenant is rejected on measurement.** One tenant schema is 55 tables + 184 indexes = **239 relations, ~3.2 MB of empty files**. At 5,000 orgs that is **~1.2M `pg_class` rows, ~6M `pg_attribute` rows, 5–6 GB of catalog, ~16 GB of empty relation files**, and — measured with pgjdbc's own `getColumns` SQL against 300 real schemas — **~3.1M rows and ~9 s of unfiltered JDBC metadata on every JVM start**. Cross-tenant fan-out fails hard at ~3,200 UNION branches (`max_locks_per_transaction=64 × max_connections=100` ≈ 6,400 lock slots, measured 2.004 locks per branch), and there are 5,000 orgs today. A 5,000-schema empty queue sweep costs **0.7–1.4 s server-side** against a **1 s** notification poll interval. Uniform schema-per-tenant is not a design we can ship into this database.

**The hybrid keeps every property that matters and pays none of those costs.** Promotion is byte-identical to extraction — the same copy, to a schema instead of a database — so the boundary is validated continuously by real promotions rather than by a one-off drill. Cross-tenant fan-out becomes **O(silos), not O(tenants)**, and silos are deliberately few (ceiling: 200 per database, §8 Q1). Signup creates no DDL at all: a new org lands in `tenant_pool` and the registration transaction is unchanged.

**The one thing the hybrid makes non-negotiable:** every `org_id` column stays, and every tenant query keeps its `org_id` predicate. In `tenant_pool` the predicate is the only thing separating tenants; in a silo it is redundant but free (~72 MB across ~4.5M rows, under 5% of the database) and it is the detector that catches a `search_path` mistake — a misrouted write produces a row whose `org_id` disagrees with its schema, which a nightly per-schema assertion can catch. Without the column a misrouted write is invisible forever and silently becomes another tenant's data. That is the worst failure this design can produce.

---

## 2. The boundary

Three homes:

- **P** — `platform` schema. One copy. Always explicitly schema-qualified in code (`@Table(schema = "platform")`, `platform.foo` in native SQL).
- **T** — the tenant schema: `tenant_pool` while pooled, `t_<32hex>` when siloed. Referenced unqualified; the `search_path` decides which.
- **P+T** — the same table exists in both schemas and the adapter routes on `org_id` nullability: non-null → tenant, null → platform. Seven tables. No call site changes for `audit_log` because `AuditLogImpl.record(action, orgId, …)` already takes the org as an argument.

### All 55 tables

| # | Table | Home | `org_id` | Why |
|---|---|---|---|---|
| 1 | `api_key` | **P** | nullable | `findByPrefix` runs against the global `uq_api_key_prefix_live` **before any tenant is known** — the key *is* how the tenant is discovered. Platform-tier keys have no org at all. |
| 2 | `api_usage_daily` | **P** | not null | `UsageExportJob`'s cross-tenant `ORDER BY day` is load-bearing fairness: orgs a deadline-cut run never reached hold the oldest days and sort first tomorrow. `agedOutDays()` is an index-only scan over `idx_api_usage_unexported` with no per-schema equivalent. |
| 3 | `audit_log` | **P+T** | nullable | Org rows are the tenant's compliance record and must travel. Null-org rows (impersonation lifecycle, platform key mint/revoke, `FeatureFlagService.set`) stay — a support investigation cannot fan out. |
| 4 | `billing_account` | **T** | not null | |
| 5 | `consent_record` | **P** | — | Person graph. |
| 6 | `document` | **P+T** | nullable | `org_id` null means *personal document*, not unknown tenant (`DATA_MODEL.md:219`, `PersonalDocumentController`). A personal document belongs to a human and must not travel. |
| 7 | `erasure_request` | **P** | — | Person graph. `ComplianceService.ERASED_TABLES` is entirely platform-side; subject erasure gets no simpler under this design. |
| 8 | `event_inbox` | **P** | — | Infrastructure. Never copied, never per-tenant. |
| 9 | `event_publication` | **P** | — | Modulith outbox. `listener_id` is a Java method signature; nothing in it is tenant-shaped. Pin with `spring.modulith.events.jdbc.schema: platform`. |
| 10 | `exchange_job` | **P+T** | nullable | V24:11 — `null = platform-scoped handler`. Org jobs are the tenant's exchange history (artifacts, retention overrides, export); platform jobs stay. |
| 11 | `exchange_job_error` | **P+T** | — | FK to `exchange_job`; follows its parent into whichever home. |
| 12 | `exchange_schedule` | **T** | not null | |
| 13 | `external_identity` | **P** | — | `uq_external_identity_subject_live(provider, issuer, external_subject)` is one-login-one-human, platform-wide. Resolved before any org exists. |
| 14 | `external_organization` | **P** | — | The routing registry: the JWT `organization` claim resolves here **before** the tenant is known. |
| 15 | `feature_flag` | **P** | — | Catalog. Snapshot-copied at extraction. |
| 16 | `feature_flag_org_override` | **T** | not null | Tenant config. Cross-tenant admin writes go through an audited `TenantContext.runAs(orgId, …)`. |
| 17 | `geo_capture_policy` | **T** | not null | |
| 18 | `geo_stamp` | **T** | not null | |
| 19 | `idempotency_key` | **P** | — | PK is `(principal, idem_key)`; `IdempotencyFilter` runs at `@Order(1)`. Transient. |
| 20 | `impersonation_session` | **P** | nullable | `ImpersonationLookupImpl.activeSession(sessionId, actorPersonId)` resolves an opaque session id at `@Order(-2)`, before any tenant is known. Hard constraint. |
| 21 | `in_app_notification` | **P** | — | Generated by org events, addressed to a *global* person. |
| 22 | `integration` | **P+T** | nullable | V33:15 — `null = platform default`. `IntegrationService:47` already branches. |
| 23 | `integration_setting` | **P+T** | — | FK to `integration`; follows its parent. |
| 24 | `legal_hold` | **P** | nullable | Must be **one** set. `SoftDeletePurgeJob` reads it cross-module as the thing that blocks hard deletion; its own javadoc narrates a previous version of exactly this bug — "it matches nothing, and a nightly job quietly resumes hard-deleting data a court said to keep." All 5,000 seeded rows are PERSON-scope with a null org. |
| 25 | `maintenance_window` | **P+T** | nullable | `MaintenanceFilter` (`@Order 4`) reads platform-wide windows on **every** request regardless of tenant; org windows are the tenant's. Two reads; cache the platform half. |
| 26 | `membership` | **T** + index | not null | See §2.1. |
| 27 | `notification_delivery` | **P** | nullable | 1 s poll, batch 200, concurrency 16, `org_id` genuinely nullable (V41 added it later), pure transport. `mart_delivery_outcomes` reads it platform-wide. Drained, not dumped. |
| 28 | `org_group` | **T** | not null | |
| 29 | `org_group_member` | **T** | — | FK to `org_group`, intra-schema. |
| 30 | `org_retention_override` | **T** | not null | |
| 31 | `org_role` | **T** | not null | |
| 32 | `org_security_policy` | **T** | not null | Read by `OrgPolicyEnforcementFilter` at `@Order(3)` — which is why the router must sit before it (§3). |
| 33 | `org_sla_override` | **T** | not null | |
| 34 | `org_subscription` | **T** | not null | |
| 35 | `organization` | **P** | — | The routing registry. Not mirrored into tenant schemas — see §2.1. |
| 36 | `payment` | **T** | not null | |
| 37 | `person` | **P** | — | See §2.2. |
| 38 | `person_contact` | **P** | — | `uq_person_contact_verified_live(kind, lower(contact_value))` is one-verified-address-one-human, platform-wide. |
| 39 | `person_preference` | **P** | — | Person graph. |
| 40 | `person_profile` | **P** | — | Person graph. |
| 41 | `plan` | **P** | — | Catalog. Snapshot-copied at extraction, after which the two deliberately diverge. |
| 42 | `plan_entitlement` | **P** | — | Catalog. |
| 43 | `role_permission` | **T** | — | `role_permission.role_id → org_role(id) ON DELETE CASCADE`, and every one of the 15,000 `org_role` rows carries a non-null `org_id`. The cascade forces it into `org_role`'s schema. It is **not** platform reference data. |
| 44 | `search_document` | **P** | nullable | See §2.1. |
| 45 | `setting` | **P** | — | Catalog. |
| 46 | `shedlock` | **P** | — | Infrastructure. Never copied — an extracted deployment that inherits a future `lock_until` silently runs no jobs at all (`@SchedulerLock` skips a same-name relock without logging). |
| 47 | `signup_request` | **P** | nullable | `SignupRequest.orgId` is set **at completion**. The row exists before its tenant does. |
| 48 | `sla_policy` | **P** | — | Catalog. Missing it → every ticket's due date is null and `lockBreached` matches nothing; SLA escalation stops without a log line. |
| 49 | `ticket` | **T** | **not null** | V36:40 is `org_id uuid not null`. (One facet listed this as nullable; verified otherwise.) |
| 50 | `ticket_message` | **T** | — | FK to `ticket`, intra-schema. 600k rows / 110 MB, reachable only through the parent join. |
| 51 | `translation` | **P** | — | Catalog. Missing it → message keys on the wire. |
| 52 | `user_device` | **P** | — | Person graph. |
| 53 | `user_device_trust` | **T** | not null | V51 made trust per-org on purpose (PK `(device_id, org_id)`). See §2.1 for the required denormalization. |
| 54 | `webhook_delivery` | **T** | not null | See §2.1. |
| 55 | `webhook_subscription` | **T** | not null | Holds the signing secret. |

**Totals: 29 platform-only, 19 tenant-only, 7 split.**

### 2.1 The contested calls, resolved

**`membership` → tenant, with a platform routing index.** Two facets disagreed here, and one of them is wrong about this codebase. The claim that "membership in the tenant schema makes every login an N-schema scan" fails against `CurrentUserProvider.fromToken` (verified, lines 164–171): the org comes from the JWT `organization` claim via `OrgLookup` → `external_organization` → `organization`. Membership is read at **step 3**, `OrgAuthorization.permissions(personId, organizationId)`, with the tenant already resolved — so it becomes a single-schema probe and the `org_id = ?` predicate disappears. Extractability decides the rest: an extracted org that cannot answer "who is in this org, with what role" without calling home is not extracted.

The only person-first read is `OrgMembershipsController.myOrganizations` and the operator's `PersonAdminController` view. Add:

```
platform.org_membership_index(person_id uuid, org_id uuid, status varchar,
                              primary key (person_id, org_id))
```

Routing keys only — no `role_id`, no `version`, no `created_by`. **Invariant, and it goes in the ADR verbatim: the index routes, the tenant schema authorizes.** It is never an authorization source. While co-located, one transaction writes both rows. After a database split the index goes eventually consistent, which is correct — a stale index only over- or under-lists the org switcher, and the authoritative check still runs on arrival.

**`organization` is not mirrored into tenant schemas.** Mirroring would resolve three of the five boundary FKs locally, but it creates a per-tenant copy of a routing row that can drift from the authority. Cut the FKs to soft refs instead — the same rule AGENTS §1 already applies 29 times over. An *extracted deployment* gets a `platform` schema of its own containing exactly its own routing rows, catalog snapshot, and person projection; that is the clean framing, and it means the extracted deployment is the same jar with the same two-schema shape.

**`search_document` → platform.** One facet wanted it per-tenant for the free index-size win. Overruled on two facts: `SearchEventListeners` writes `new SearchDoc(null, "user", personId, email, email)` — person docs have no org and cannot go per-tenant — and `uq_search_entity(entity_type, entity_id)` has no org column, so per-schema it becomes 5,000 local constraints over `varchar` UUIDs. The deciding argument is that it is **derived data**: extraction *rebuilds* the index in the new deployment from the tenant's own rows rather than copying it. Keeping it platform also preserves `AdminSearchController`'s `CANDIDATE_CAP = 2000` trick, which holds unscoped platform search at 18 ms regardless of corpus size and has no per-schema meaning. If per-tenant index size ever becomes a real problem, the answer is declarative partitioning on `org_id`, not schema tenancy.

**`api_key` → platform, whole.** One facet recommended a two-probe split (`platform.api_key_route(prefix, org_id)` + the secret in the tenant). Overruled: the extraction bundle **re-mints** every key anyway — the platform revokes X's keys and permanently reserves their prefixes — so the secret never travels regardless, and the split buys only defense-in-depth against a platform compromise, which schema separation inside one database does not provide. It costs an extra index probe on every machine request, a second write path, and a consistency obligation, for nothing. Listed as an open question (§8 Q4) so the owner can overrule.

**The three queues split two ways, and the facets disagreed. The split is deliberate:**

- `notification_delivery` → **platform**. Highest rate (1 s poll, batch 200), `org_id` genuinely nullable, pure transport, and `NotificationDeliveryQueue.claim` is a cluster-wide `SKIP LOCKED` sweep. Per-tenant, discovery costs 0.7–1.4 s server-side per empty sweep against a 1 s interval. Accepted cost, stated: one tenant's backlog delays another's — true today. Mitigate with fairness (below), not with per-tenant tables.
- `webhook_delivery` → **tenant**. The deciding fact is the secret: `WebhookDeliveryQueue.claim` joins `webhook_subscription` and returns `s.secret` on every claim, and a signing secret is the tenant's credential. Keeping the delivery row in `platform` while the subscription is in the tenant would also make `webhook_delivery.subscription_id → webhook_subscription` a sixth boundary FK. 5 s poll and batch 50 tolerate the signal indirection.
- `exchange_job` → **split by nullability**. Not transient: it is the tenant's exchange history with artifacts, retention overrides, and export obligations. One job per claim, so the indirection is amortized over a long job.

All three claims go through one new table:

```
platform.queue_signal(queue text, org_id uuid, due_at timestamptz,
                      primary key (queue, org_id))
```

Enqueue upserts one signal row per **batch**, not per row. Claim becomes two steps: claim a *tenant* from `queue_signal` with the exact `FOR UPDATE SKIP LOCKED` idiom already in `WebhookDeliveryQueue`, then run today's claim query with `and org_id = ?` appended and the `search_path` pinned. Release recomputes `due_at` from the tenant's remaining rows — PENDING `next_attempt_at` **plus** PROCESSING `locked_at + staleLock`, do not lose the stale-reclaim arm — and deletes the row when nothing is left. A stale signal is harmless: a worker that finds nothing deletes it.

This does three jobs at once. It makes the cross-schema join in `WebhookDeliveryQueue.claim` *expressible at all* (the tenant is pinned before the query runs, so `webhook_subscription` resolves unqualified and the two-arm UNION ALL survives byte-for-byte with one extra equality predicate). It makes discovery O(due tenants) instead of O(tenants). And it makes fairness structural: one signal row per tenant means one tenant holds at most one claim slot — which makes the outage documented in `WebhookDeliveryQueue.claim`'s javadoc, where one paused subscription's rows occupied every slot until *no tenant* got a webhook, impossible by construction.

**`user_device_trust` → tenant, with a denormalization worth doing anyway.** `isTrusted` is a native query joining the global `user_device` for `person_id`, `fingerprint` and `deleted_at is null`, and that join is load-bearing — its javadoc says a revoked device whose grant survived would otherwise keep passing the policy. Cross-schema, on every request of an org with `require_trusted_device`. **Copy `person_id` and `fingerprint` into the grant row** so the check is a single-schema probe, and have device revocation publish an event that deletes the grants. Worst case becomes a revoked device trusted for seconds instead of an impossible join — and it is faster today, not only extractable later. This replaces the `ON DELETE CASCADE` that the FK cut removes, and `SoftDeletePurgeJob.PURGE_ORDER` gains a case for it. The last commit on this branch fixed a device-trust bypass; a stale trust row is the same class of bug, so this needs its own test.

### 2.2 `person` is global. What travels is a projection.

The `person` row does **not** travel. What travels is a copy: for every `person_id` appearing in the tenant's `membership` and in `created_by` / `updated_by` / `*_person_id` across its tables — id, `formatted_name` / `given_name` / `family_name`, `status`, and the one contact the org actually uses.

**`person.id` is the join key and it does not change on extraction.** The same UUIDs land on the other side, so `created_by` stays meaningful. That is exactly what the existing no-FK soft-ref convention bought: there was never a foreign key to break. It is also why the schema has **zero sequences and zero identity columns** — every PK is a UUID, so rows move out, move back, or merge with no id remapping. That single fact is what makes every step in §6 reversible for free.

Nothing else about the human travels. `external_identity`, verified `person_contact` rows, `person_preference`, `person_profile`, `user_device`, `consent_record`, `erasure_request`, `in_app_notification` all stay. **Copying `external_identity` or a verified contact would put two systems in violation of `uq_external_identity_subject_live` and `uq_person_contact_verified_live`** — one-login-one-human and one-verified-address-one-human. Copy contacts as *unverified display projections* or not at all.

When a person belongs to orgs X, Y and Z and X extracts, **nothing happens to Y and Z**. The person stays whole on the platform; X gets a projection; the two drift. That drift is the price, and it goes in the ADR: *after extraction, a name change on the platform reaches X asynchronously or not at all, and the tenant's own member list is the tenant's truth.* Mitigate with a `person.updated_at`-driven feed, never a distributed transaction.

**The multi-org person is legal, unmodelled by any fixture, and therefore untested.** `uq_membership_org_person_live` is `UNIQUE(org_id, person_id) WHERE deleted_at IS NULL`; the seed has 193,940 people each in exactly one org and **zero** in more than one. Every fan-out question in this document is untested by the fixture. Adding a multi-org tenant to the seed is Phase 0's first task, because otherwise the suite is green through the entire migration and proves nothing.

---

## 3. Runtime

### 3.1 The mechanism: a routing DataSource, not `TenantSchemaMapper`

Hibernate 7.4.1 has `hibernate.multi_tenant.schema_mapper` (`@Incubating`, `@since 7.1`, present in this build; `AbstractSharedSessionContract:855-873` sets and restores the schema around each connection). **Do not use it.** Three measured reasons:

1. `PgConnection.setSchema` emits `SET SESSION search_path TO '<literal>'` — a **single-element** path. `public` drops out entirely. You cannot smuggle a list through it: `SET SESSION search_path TO 't1, public'` sets the path to one schema literally named `"t1, public"`, `current_schema()` returns NULL, and every unqualified name fails. Verified.
2. That breaks `pg_trgm`, which this codebase depends on. `SearchQueryService:100,127` uses `word_similarity(?, d.title)` and the `%` operator in native SQL; `V22__search.sql:35` builds `using gin (title gin_trgm_ops)`. Under a single-element path all four fail.
3. It covers Hibernate only. **22 classes take `JdbcTemplate` or `DataSource` directly** — all three queues, `SoftDeletePurgeJob`, `UsageExportJob`, `GatewayUsageReportController`, `OrgExportService`, `ComplianceService`, `SearchQueryService`, `SearchIndexStore`, `EventInbox`, `IdempotencyStore`, `SoftDeleteRecovery`, `DuckDbAnalyticsEngine`, `HaversineGeoSearch`, `ExchangeJobStore`, `ExchangeWorker`, and more — plus ShedLock's `JdbcTemplateLockProvider`, Modulith's JDBC registry, and Flyway. All of them would run on the pool default.

Also: a null tenant identifier is **not an error** in Hibernate's schema MT — `useSchemaBasedMultiTenancy()` returns false and it silently skips `setSchema`. That is a fail-open default in the framework.

**Instead:** `TenantRoutingDataSource` (`@Primary`) wrapping the Hikari pool (`@FlywayDataSource`, not primary).

**The one rule: always set on borrow, never reset on close.** Reset-on-close is the fragile half — skipped when `close()` throws, when the connection is evicted, when someone unwraps the proxy. Unconditional set-on-borrow makes it unnecessary: the next borrower always overwrites, so there is nothing to leak.

| `TenantContext` state | statement issued on borrow |
|---|---|
| `TENANT(orgId)`, pooled | `SET search_path TO tenant_pool, ext` |
| `TENANT(orgId)`, siloed | `SET search_path TO "t_<32hex>", ext` |
| `PLATFORM` | `SET search_path TO platform, ext` |
| absent (the default) | `SET search_path TO no_tenant` |

Cost: **one round trip per borrow**, 0.080 ms measured on this box — one fifth of Hibernate's own path (`getSchema` + `setSchema` + release `setSchema` = 5 round trips through Hikari, since `ProxyConnection.setSchema` re-reads after writing).

Schema names derive from `organization.id`: `"t_" + uuid.toString().replace("-","")` — 34 chars, well under NAMEDATALEN 63, deterministic, **zero database reads to route**. Never the alias: `OrganizationService.rename` exists and `uq_organization_alias_live` is partial, so an alias is both mutable and reusable. Validate against `^t_[0-9a-f]{32}$` before interpolation; that regex is the only thing between a schema name and SQL injection.

**Schema layout.** `platform` (never on a tenant path — reached by explicit `@Table(schema = "platform")`, which Hibernate honours regardless of `search_path`); `tenant_pool`; `t_<hex>` per silo; `ext` holding `pg_trgm` only (`ALTER EXTENSION pg_trgm SET SCHEMA ext` — it is relocatable); `no_tenant`, a real but **empty** schema; `public` left with nothing in it. The critical property: **no schema on any tenant's `search_path` holds another tenant's rows, and fallthrough reaches functions only.** That is what makes a multi-element path safe here where it usually is not.

Belt and braces: set `spring.datasource.hikari.schema: platform`. Today `PoolBase.java:243`'s reset is inert because `HikariConfig.getSchema()` is null; setting it makes Hikari reset the path for any borrower that goes through `Connection.setSchema()`. Note the sharpest trap in the whole design: **a raw `SET search_path` issued through a `Statement` never sets `DIRTY_BIT_SCHEMA`, so Hikari never resets it.** Set-on-borrow survives that; reset-on-close would not.

### 3.2 Where the tenant is pinned

`TenantContext` is a `ThreadLocal<Tenant>` with three states — `TENANT(uuid)`, `PLATFORM`, absent. **Absent is not null-meaning-default; it is the poison state.** Give it zero Spring dependencies: a bare holder in `shared`. That sidesteps the eager-init hazard — Hibernate instantiates strategy beans during EMF creation, so a resolver injecting a repository would be a circular-initialization bug at boot.

**Pin it inside `CurrentUserFilter` (`@Order(-1)`), not in a new filter.** Two facets disagreed on placement: one proposed `@Order(6)`, after `SubscriptionAccessFilter`. That is wrong on its own evidence — `OrgPolicyEnforcementFilter` (`@Order 3`) reads `org_security_policy` and `user_device_trust`, and `SubscriptionAccessFilter` (`@Order 5`) reads `org_subscription`, and all three are tenant tables under §2. The router must be before them. `CurrentUserFilter` already resolves and memoizes the `CurrentUser`, already clears in a `finally`, and already runs after `ImpersonationFilter` (`@Order -2`) has swapped the `Authentication` — so an impersonated request pins the *target's* org, which is exactly right per AGENTS §5.5. Extending it means one filter, one lifecycle, one `finally`; a separate filter would duplicate that discipline and could drift out of order.

Two other entry points install it:
- `McpToolDispatcher.callOnCallerContext` — one `TenantContext.set(...)` beside the existing `SecurityContextHolder.setContext(...)`, restored in the same `finally`. Its javadoc already states this obligation for `currentUser()`; this is the second half of it. This also turns `McpWriteGuard`'s tenancy discipline from a code-review convention into something the connection enforces — the biggest structural win of the whole design.
- Jobs and workers — explicitly, per tenant, per iteration.

**Enforce with a test, because it is the #1 bug people will write:** `TenantContext.set()` must **throw** when `TransactionSynchronizationManager.isActualTransactionActive()`. The schema is chosen at connection *borrow* and Spring binds one connection per transaction, so setting the tenant inside a `@Transactional` method silently does nothing. Making it throw converts a data-corruption bug into a startup-obvious one.

**Do not register a `ThreadLocalAccessor`.** `io.micrometer:context-propagation` is on the classpath, so one would make the tenant flow into `@Async` module listeners — but `OutboxResubmissionJob` has no thread context at all, so the immediate and retried deliveries of the same event would behave differently. **Async listeners and outbox retries both take the tenant from the event.** This costs nothing: of the 15 domain event records carrying `occurredAt`, **10 already carry `orgId` as the first component**, and the 5 that do not (`SettingChanged`, `FeatureFlagChanged`, `TranslationChanged`, `PersonActivated`, `PersonProvisioned`) map exactly to tables with no `org_id`.

Note also: `spring.threads.virtual.enabled: true` means request handling runs on threads that are never reused, so a forgotten `TenantContext.clear()` cannot leak between HTTP requests. The bug will only ever appear on pooled platform threads — the scheduler, `@Async` executors, the MCP SDK's threads. **Test the clearing on those paths specifically; the HTTP tests will pass regardless.**

### 3.3 How a tenant-less request fails closed — four layers

1. **`CurrentUserFilter` refuses.** If the route is tenant-scoped and `organizationId()` is null → `ForbiddenException` → 403 `FORBIDDEN` through `GlobalExceptionHandler`, in the envelope, with the request id. The clean, intended failure. Needs an explicit allowlist for routes that legitimately have no org: `/admin/**`, `/api/v1/signup*`, the Kill Bill and Pesapal callbacks, `/internal/gateway/**`, `/mcp` platform-tier calls, and the actuator/docs matchers already in `SecurityConfig`.
2. **Absent → `no_tenant`, unconditionally.** Never "leave the connection as it is", never "fall back to platform". The pre-auth borrows (`ApiKeyAuthenticationFilter` at `@Order -100`) land here and are fine: they touch only explicitly-qualified platform tables.
3. **`no_tenant` is empty**, so any unqualified tenant table → `relation "ticket" does not exist` → 500 with the request id. Deliberately ugly; unreachable because of (1).
4. **Even a total bypass cannot cross tenants**, because no schema on any `search_path` holds two tenants' rows — except `tenant_pool`, where the `org_id` predicate is the separator, which is why §1 makes keeping it non-negotiable.

There is no path on which "no tenant" resolves to a schema containing someone else's data.

### 3.4 Background work

Give every one of the **14** `@SchedulerLock` jobs an explicit axis:

- **PLATFORM, runs once:** `EventPublicationPurgeJob`, `EventInboxPurgeJob`, `IdempotencyPurgeJob`, `OutboxResubmissionJob`, `IdentityReconciliationJob`, `UsageExportJob`, `NotificationRetentionJob`.
- **PER-TENANT, loops:** `SoftDeletePurgeJob`, `WebhookRetentionJob`, `ExchangeRetentionJob`, `ExchangeScheduleFiringJob`, `SlaEscalationJob`, `TrialExpiryJob`, `DunningJob`.

The iteration source is `tenant_pool` plus `select schema_name from platform.tenant_placement where state = 'SILO'` — **bounded by the silo ceiling, not by tenant count.** Each tenant gets its own transaction, which the routing DataSource enforces anyway.

Keep **one** ShedLock lease per job — not one per tenant, which would put N rows in `platform.shedlock` and mean `lockAtMostFor` no longer bounds the run. Make the loop **resumable**: persist the last schema processed so a lease expiry continues rather than restarts. `UsageExportJob`'s `max-backlog-days` window is already this pattern; generalize it. Re-derive every `RUN_DEADLINE` — `UsageExportJob` and `IdentityReconciliationJob` use 25 m against a PT30M lease precisely so a slow pass never overruns into a second instance's concurrent run, and that guarantee must be re-derived per fan-out job, not assumed.

### 3.5 Caches (ADR 0004)

Four of the seven caches are already tenant-safe and two must **never** become tenant-scoped:

| cache | key | verdict |
|---|---|---|
| `person-by-subject` (`PersonResolutionCache:54`) | `provider\|issuer\|subject` | **GLOBAL — mandatory.** Read before any tenant exists. |
| `org-by-external-id` (`OrgResolutionCache:74`) | same | **GLOBAL — mandatory.** |
| `org-permissions` (`PermissionResolver:37`) | `#organizationId + ':' + #personId` | TENANT; drop the hand-rolled prefix. |
| `org-entitlements` (`EntitlementResolver:32`) | `#orgId.toString()` | TENANT; drop the hand-rolled prefix. |
| `setting-values` (`SettingService:50`) | `#key` | GLOBAL — `setting` has no `org_id`. |
| `feature-flags` (`FeatureFlagService:34`) | `#key` | GLOBAL. `isEnabledFor(key, orgId)` stays deliberately uncached. |
| `translation-bundles` (`TranslationBundles:25`) | `#locale` | GLOBAL. |

The exposure is not today's keys — it is that `TwoLevelCacheManager.getCache` creates any name on demand, so the **next** `@Cacheable` over an org-scoped table leaks silently, and only after L1's 60 s TTL, giving the same intermittent-failure signature `PersonResolutionCache`'s javadoc already documents for the UUID/JSON trap.

The fix is structural, not per-cache: a **cache registry** where every name is declared `GLOBAL` or `TENANT`, `getCache` **fails** on an undeclared name, `TwoLevelCache` prefixes `t_<hex>|` into both L1 and L2 keys for TENANT caches and **throws** when `TenantContext` is absent, and one test enumerates every `cacheNames` value in `@Cacheable`/`@CacheEvict` and asserts it is declared — the same shape as `SoftDeletePurgeJobIntegrationTest.purgeOrderCoversEverySoftDeletableEntity`.

Two related notes: **raise `app.cache.l1-max-size` (10,000) or partition L1** — it is now shared across tenants and a hot tenant evicts everyone else. And `SubscriptionService:237,248` evict `org-entitlements` with `allEntries = true`, which already nukes every org's entitlements on any single plan change; under prefixed keys it stays correct and stays wasteful. Fix it to a keyed evict while you are in there.

---

## 4. Migrations and provisioning

### 4.1 V1–V52 split by directory, keeping their numbers

Rewrite the 52 files **in place** — pre-production, precedent set by commit `1390706`, and AGENTS §4.5's "never renumber" is a rule about *applied* migrations that a pre-production reset suspends deliberately, once, in writing.

But **do not renumber and do not squash.** There are **412 references to migration version numbers in prose** — 113 in `src/main/java`, 116 inside the migration headers themselves ("V17's rule", "V42's point", "the reason given in V10's header"), 183 in `docs/`. Renumbering makes all of them lie. And the 2,589 lines of decision-rationale headers are the repo's most valuable migration asset (AGENTS §4.5 names V10/V11 as the reference voice).

```
src/main/resources/db/migration/platform/V<n>__<name>.sql
src/main/resources/db/migration/tenant/V<n>__<name>.sql
```

Each file keeps its current V-number. Flyway does not require contiguous versions — each sequence simply has gaps, and each gets its own `flyway_schema_history` in its own schema. **The counter rule stays exactly as AGENTS §4.5 states it, one line longer: one global counter; a new migration takes the next global number and lands in exactly one directory.** "V53 is taken" keeps meaning what it means today.

Files that create both kinds get **bisected, not duplicated**. V11 is the archetype: `platform/V11__organization_rbac.sql` keeps `organization` + `external_organization`; `tenant/V11__org_rbac.sql` keeps `org_role`, `role_permission`, `membership`. Each half keeps the paragraph of V11's header that applies to it and gains one sentence pointing at its sibling. The files needing bisection are V11, V13, V17, V19, V20, V22, V23, V24, V25, V26, V31, V33, V34, V35, V36, V45, V49, V50, V52 — but the **authoritative list is generated mechanically** from the tier table in §2 by a script, which is Phase 2's first deliverable. Do not hand-curate it.

The seven split tables (`audit_log`, `document`, `exchange_job`, `exchange_job_error`, `integration`, `integration_setting`, `maintenance_window`) get **identical DDL in both directories** — same table, two homes.

**Extension placement:** `create extension pg_trgm schema ext` in a platform migration. Never depend on `public` being on a tenant path — that is what makes a tenant liftable to its own database.

**Rebuild the org_id indexes.** Every `(org_id, …)` composite that V49/V50/V52 built — `idx_document_org_recent`, `idx_audit_org_occurred`, the ticket org indexes — keeps its shape in `tenant_pool` (where `org_id` is still selective) but becomes a leading column with one distinct value in a silo. The silo DDL drops the `org_id` prefix so the planner uses `created_at desc` directly. **This is a per-index review, not a blanket rule, and it is the largest piece of work nobody will budget for.**

**Missing indexes to add in Phase 0** (measured: `notification_delivery` by org is a `Parallel Seq Scan`, 159 ms, 6,611 buffers, 99,980 rows discarded to return 60): `notification_delivery`, `impersonation_session`, `feature_flag_org_override`, `signup_request` all lack an `org_id`-leading index.

### 4.2 Flyway configuration

There is **no `spring.flyway.*` block in `application.yaml` today** — pure autoconfiguration onto `current_schema()` = `public`, one history table with 52 successful rows.

- **Platform migrations stay at boot, unchanged.** One schema, ~29 files, ~1 s, advisory-lock-serialized across replicas. `spring.flyway.locations: classpath:db/migration/platform`, `default-schema: platform`, `create-schemas: true`, `schemas: platform,ext,no_tenant,tenant_pool`. Bind it to the **raw** Hikari bean (`@FlywayDataSource`) — the routing DataSource would hand it `no_tenant`, and Flyway 12.4.0 carries the error string *"Unable to determine current schema as search_path is empty"* for exactly that.
- **Tenant migrations run in a separate, resumable job** — `tenant_pool` first, then each silo. Not at boot: `deploy/helm/smsone/templates/modulith.yaml` has **no `startupProbe`** and `livenessProbe initialDelaySeconds: 60, periodSeconds: 15, failureThreshold: 3` gives a **~105 s budget** before the kubelet kills the pod, and Flyway runs before the servlet container serves.

Runner design, from measurement:
- **Per-schema Flyway overhead is 50–150 ms even with zero pending migrations** — a fresh connection, four probe queries (`SELECT CURRENT_USER`, the `rds_superuser` check, `current_schema`, `SHOW search_path`), a full classpath rescan and re-checksum of every script (`FlywayExecutor.createResourceAndClassProviders` builds `new Scanner<>` per `migrate()`, and `validateOnMigrate` defaults true). **Supply both `resourceProvider` and `javaMigrationClassProvider`** and the Scanner is skipped entirely.
- **Parallel, 8–16 workers.** Safe because `PostgreSQLConnection.lock` derives its advisory-lock discriminator from `quote(schema, "flyway_schema_history").hashCode()` — the lock is **per schema**, so parallel tenant migration does not serialize.
- **Never abort on a tenant failure.** Record it, continue, exit non-zero with a manifest. This is not new doctrine — `SoftDeletePurgeJob`'s javadoc already states it: *"Loud AND complete, not loud instead of complete."*
- **Registry:** `platform.tenant_placement(org_id, schema_name, datasource_name, state, schema_version, last_error, updated_at)`. Partial-fleet state must be a queryable fact, not an incident.
- Full 52-migration replay into a fresh schema is **~200–330 ms** and **flat**, not degrading (313 ms/schema for the first 20, 267 ms for the next 80, 327 ms at 300). A trivial migration across 500 schemas is **806 ms**.

**Forbid `executeInTransaction=false` in tenant migrations by default.** With Postgres's transactional DDL and the default, a failure rolls back cleanly and writes **no** history row — the schema sits at V(n−1), internally consistent, and a re-run retries it. The non-transactional case (the `.sql.conf` sidecar of AGENTS §4.6 — and note the header-comment form does *not* work on flyway-core 12.4.0) writes `success=false` and wedges that schema until `repair()`. One flaky `CONCURRENTLY` across the fleet is one manual repair per silo. AGENTS §4.6's own argument — plain DDL until the lock hurts — gets *easier* here, since per-tenant tables in a silo are orders of magnitude smaller.

### 4.3 Provisioning a new tenant

**Nothing changes.** A new org lands in `tenant_pool`. `SignupService.verify` → `Organizations.create` → `OrganizationService.create` → `OrgProjectionWriter.projectWithOwner` keeps its current single atomic transaction. No DDL, no `PROVISIONING` status, no new event, and the three `@ApplicationModuleListener`s on `OrganizationRegistered` — `OrganizationTrialListener` (`org_subscription`), `OrganizationBillingListener` (`billing_account`), `SearchEventListeners` (`search_document`) — keep working because `tenant_pool` already exists.

This is the strongest practical argument for the hybrid. Under uniform schema-per-tenant those three listeners would fire after-commit against a schema that does not exist yet, every new tenant would silently get no trial and no billing account, and because they are outbox-retried the failures would look like transient noise.

**Schema creation happens only at promotion** (§6, §7 Phase 5), where it is an operator-initiated runbook step with a freeze window, not a signup-path latency question.

### 4.4 Version skew: the code trails the schema

Today's rule (AGENTS §4.6) governs one axis: old binary vs. new schema. This adds a second — **schema vs. schema, at the same instant, with one binary talking to both**, because the fan-out commits per schema and cannot be one transaction (lock-table arithmetic, §5).

> **A tenant migration ships in release N; the code that depends on it ships no earlier than release N+1, and only after the fan-out has recorded 100% of tenant schemas at that version.**

This inverts today's ordering — code now trails schema — and it falls straight out of expand-contract. Tenant migrations are expand-only, so a binary at head reading a schema at head−1 sees a **missing** column, which is a runtime failure. A binary at head−1 reading a schema at head sees an **extra** column, which §4.6 already guarantees is safe. The safe skew direction is schema-ahead, and the deploy order must produce it.

Enforcement, so this is not a wiki page nobody reads: the binary carries a compiled-in `MIN_TENANT_SCHEMA_VERSION`; `CurrentUserFilter` compares it against `platform.tenant_placement.schema_version`; a tenant below the floor gets **503 + `Retry-After`**, never a query against a schema missing a column it will select. A tenant *above* head (rolled-back binary) is served normally. That is AGENTS' own doctrine — fail closed on authorization, fail loudly in migrations — applied to a failure mode that did not exist before.

**`spring.jpa.hibernate.ddl-auto` goes from `validate` to `none`.** Two reasons: boot-time validation borrows through the routing DataSource with `TenantContext` unset and would validate against the empty `no_tenant`; and `hibernate.default_schema` is set nowhere in the repo, so `GroupedSchemaValidatorImpl`'s namespace carries no schema and pgjdbc's `getColumns` runs **unfiltered** — measured at **189,293 rows / 562 ms across 300 schemas**. Replace it with an explicit post-`ApplicationReadyEvent` check that validates `platform`, `tenant_pool`, and one canary silo. This is a real loss of a safety net and needs its own test, not a silent removal.

**`FlywayBaselineTest` reads `flyway_schema_history` unqualified.** Qualify it, and add a sibling assertion that every tenant schema's history is at the same version as `tenant_pool` — a silo stuck two migrations behind is the failure mode per-tenant migration introduces, and nothing else will notice it.

---

## 5. What gets worse

No softening. Each item names its replacement.

**1. The platform support ticket queue.** `TicketRepository.pageForQueue` keyset-paginates by `(created_at desc, id desc)` across every org. There is no honest N-way merge that respects a cursor. Measured: a 50-branch UNION costs 12–24 ms planning / ~102 locks; 200 branches 69–100 ms / ~402 locks; 500 branches 313–389 ms / **1,002 locks**. Planning is ~0.5–0.66 ms per branch and **does not amortize** — warm re-runs pay it again, and a 5,000-branch query text is ~730 KB, which defeats both the JDBC statement cache and Postgres' plan cache.
**Replacement:** while pooled, a UNION of `tenant_pool` + the silos, bounded by the silo ceiling (§8 Q1). At 200 silos that is ~130 ms of planning per query — acceptable for an operator listing, and the reason for the ceiling. **Trigger for the next step:** when silo count crosses 50, build `platform.ticket_index(ticket_id, org_id, status, priority, created_at, resolution_due_at, escalated, subject, assignee_person_id)` fed by the existing event-listener + `EventInbox` mechanism, the pattern `search_document` already proves.

**2. `SlaEscalationJob`.** It runs on cron `15 * * * * *` — six fields, second=15, so **once a minute at :15**, not every 15 seconds (one facet misread this; verified). `TicketRepository.lockBreached` becomes a fan-out with `PESSIMISTIC_WRITE`/`SKIP LOCKED` per schema.
**Replacement:** read `platform.ticket_index` (once it exists) and dive per-tenant only to write the escalation, so N is the breach count (≤ `BATCH`), not the tenant count. Until then, loop the bounded silo list with a resumable cursor and re-derive the `PT5M` `lockAtMostFor`, which is sized for one pass over one schema.

**3. `SoftDeletePurgeJob`.** 24 ordered tables, one PT30M ShedLock lease, `MAX_BATCHES = 100`, and a `legal_hold` anti-join appended to every batch delete on 18 of them. Per-tenant that becomes (silos+1) × 24 statements a night, and the hold guard becomes a **cross-schema** anti-join in a hot delete loop. The javadoc already warns this predicate is "the mismatch that does not throw" — it matches nothing and silently resumes deleting data a court said to keep.
**Replacement:** split by tier. Platform tables purge exactly as today. Tenant tables purge per schema with a per-tenant batch budget and a resumable cursor. **Prefetch the hold set per tenant into the batch** instead of joining cross-schema, and keep the assertion test that proves the guard matches. `PURGE_ORDER`'s only real ordering constraint (`membership.role_id → org_role`) is intra-tenant and survives; add the `user_device_trust` case the cut cascade requires. `sweepSearchResidue` — the documented `work_mem` cliff where the wrong join shape did not finish in ten minutes at 200k people versus 212 ms for the anti-join — is **unaffected**, because both `person` and `search_document` stay platform. If either ever moves, the cliff is back.

**4. `/me/organizations` regresses from 2 queries to 1 + N.** `OrgMembershipsController` batches `organizations.findAllById(...)` and `members.roleCodesByIds(...)` precisely so a dual member does not pay per-org queries, and the comment in that file says so. Post-split the role code lives in each tenant schema and the batching is impossible. N is bounded by *the caller's own* org count (1 for 100% of the seeded population), not by tenant count, so it survives — but it is a knowing regression against a comment that specifically fixed this, and it goes in the ADR so the next reader does not re-optimize it back into a broken batch.

**5. `GET /api/v1/audit` with no `org` parameter changes meaning.** After the split it returns **platform-scope rows only**, and the OpenAPI description says so. `?org=X` reads X's schema. Do not build a bounded multi-schema union; there is no cursor semantics that survives it. Per AGENTS §14 and the api-guide must-update rule, this is a documented API-surface change that updates `docs/guides/api-guide.html` in the same slice.
**Second-order:** `impersonation_session` stays platform while tenant audit rows carry `impersonation_id`, so an extracted org cannot resolve the operator's stated reason — hence item 5 of the extraction bundle (§6).

**6. `MaintenanceService.activeFor(orgId)` becomes two reads per request.** `MaintenanceFilter` runs at `@Order(4)` on every `/api/` request. Cache the platform half aggressively — the repo comment already notes it is "few rows".

**7. Every connection borrow costs one extra round trip** (0.080 ms measured locally; 1–2.5 ms same-AZ is the number that matters in production).

**8. The plan cache dies on every tenant switch.** Verified: Postgres 18.4 **does** revalidate cached plans when `search_path` changes, even past the 5-execution generic-plan threshold — so there is **no cross-tenant leak through prepared statements**, contrary to most write-ups on this topic. The cost is that a pooled connection ping-ponging between silos re-parses and re-plans constantly, and pgjdbc's `prepareThreshold` (default 5) keeps re-preparing. Invisible in a single-tenant benchmark. Consider raising `maximum-pool-size` (currently 16) so hot tenants get connection affinity by luck, and measure.

**9. Relcache growth per backend, and `maxLifetime` becomes a memory knob.** Each backend caches metadata for every relation it touches with no eviction. HikariCP's `maxLifetime` (default 30 min, not overridden) is what recycles those backends. It stops being a failover setting and becomes the thing bounding backend memory. **Do not raise it.**

**10. Catalog cost, scaled to the ceiling.** 239 relations and ~3.2 MB of empty files per silo. At 200 silos: ~48,000 relations, ~650 MB of empty files. Acceptable. At 5,000 it is not, which is the decision.

**11. `pg_dump -n <one schema>` is catalog-bound, not data-bound** — 2.9 s / 86 KB for an *empty* tenant schema at 300 schemas in the database. Compliance-export latency degrades with fleet size, not with the exporting tenant's size.

**12. `drop schema … cascade` has a hard ceiling: 15 schemas per transaction.** 439 lockable relations per tenant schema against ~6,400 slots; 6400/439 = 14.6, measured exactly. Then `ERROR: out of shared memory` and the whole DO block rolls back. **One schema per transaction, always.**

**13. `AbstractIntegrationTest` shares one singleton Postgres across every cached Spring context.** Every context now needs `platform` + `tenant_pool` migrated, plus a schema per silo the test creates. At ~200 ms of DDL per schema, budget for it, and consider a test-only fast path that clones a template schema rather than replaying the set.

**14. `DuckDbAnalyticsEngine` sources are constrained going forward.** `AnalyticsReport.sourceSql` may read platform tables only. Both current marts (`select status from person`, `select channel, status from notification_delivery`) are unaffected. A future report needing tenant rows must read a per-tenant aggregate — which contradicts the existing javadoc's deliberate "don't pre-aggregate in sourceSql", and that note needs scoping to platform sources.

**15. Cross-tenant admin writes become explicit.** `FeatureFlagService.setOrgOverride(key, orgId, enabled)` writes another org's table from a platform-admin route; same shape in `compliance` (`OrgExportService`), `subscription` (admin plan assignment), `maintenance`. Each needs an audited `TenantContext.runAs(orgId, …)`. This is a deliberate cross-tenant capability and should look like one at the call site — the machine equivalent of impersonation, and it deserves the same visibility.

**Two things get better, and they are worth naming.** `OrgExportService`'s 21 hand-maintained `Extract` rows stop being the source of truth for migration-away (a tenant table added in V60 lands in `pg_dump -n` automatically; today, forgetting to add it silently under-exports and nothing catches it). And `ExchangeRetentionJob`'s two-pass `RetentionOverrides` shape collapses to one lookup per tenant with the `excludeOrgs` `NOT IN` list gone entirely.

---

## 6. The extraction journey

Four hops. Each is independently reversible until hop 2.

**Hop 0 → 1: `tenant_pool` → `t_<hex>` in the same database (promotion).**
Create the schema, run the tenant migration set, copy the org's rows with `CREATE TABLE AS SELECT … WHERE org_id = ?` (measured: 1.72 s for 150k rows across the 8 largest tables, including the `ticket_message` parent join), verify, flip `platform.tenant_placement`, evict `OrgResolutionCache`. The freeze window is an org-scoped `maintenance_window` row in `RESTRICT` mode — **but that gates HTTP org paths only**; the notification worker, webhook dispatcher, exchange runners, `OutboxResubmissionJob` and retention purges all write tenant rows outside any request and must be paused for that org too (via `platform.queue_signal` and a job-side placement check).
**Reversible by:** copying back and flipping one row. Data has not left the database.

**Hop 1 → 2: own database.** An `AbstractRoutingDataSource` keyed by `tenant_placement.datasource_name`. Mechanically a day's work; three things silently stop working:
1. **`@Transactional` stops spanning the boundary.** `PermissionResolver` walks `membership → org_role → role_permission` in one transaction alongside `external_organization → organization → person`. Split, those are two transactions on two connections with no consistency between them. Nothing *fails* — it just becomes able to read a person deleted a millisecond ago.
2. **463 MB of person graph cannot come** — 25% of the database, belonging to no tenant. The silo gets a projection (§2.2) fetched through `PersonLookup` and cached; writes to `person`, `person_contact`, `external_identity` stay platform-only forever, because the two global uniqueness constraints cannot be enforced from a silo.
3. **Default-deny becomes an availability coupling.** `CurrentUserProvider` default-denies on an absent port. In-process, absent means the database is down. Over HTTP it means *someone else's service* is down and this tenant goes dark. Mitigate by serving `person-by-subject` and `org-by-external-id` stale-while-platform-unreachable with a hard ceiling (15 min). **Never for `OrgAuthorization`** — a revoked membership must still deny the very next request, the property AGENTS §5.5 makes a hard rule.

**Cutover mechanism: logical replication with row filters.** Postgres 15+ supports `CREATE PUBLICATION … FOR TABLE ticket WHERE (org_id = '<id>')`, every one of the 55 tables has a primary key so default `REPLICA IDENTITY` suffices, there are no sequences to reseed, and initial sync runs online. The read-only window is then only: open the freeze → wait for `pg_replication_slots.confirmed_flush_lsn` to reach `pg_current_wal_lsn()` → flip placement → evict caches → drain the pool → close. **Seconds to tens of seconds, independent of tenant size.** `wal_level` is `replica` today; `logical` requires a **restart**, which is why it is a Phase 0 item.
**Reversible by:** flipping `datasource_name` back and reverse-replicating. This is the last cheap reversal.

**Hop 2 → 3: own deployment.** Mostly configuration, plus four things that must be **fresh and empty, not copied**: `event_publication`, `event_inbox`, `idempotency_key`, `shedlock`. Copying `shedlock` gives two deployments the same lock names and the new one silently runs nothing; copying `event_publication` replays events the platform already delivered — duplicate webhooks, duplicate notifications, duplicate indexing. Also fresh: the Valkey cache/rate-limit key prefixes and the SeaweedFS bucket root. Routing moves to the gateway (route by the `organization` claim to the tenant's upstream); the gateway subsystem already exists.

**Hop 3 → 4: own service.** The reframe that makes this cheap: **the tenant never becomes a service — the platform becomes one, and the tenant deployment is the same jar with different wiring.** `PersonLookup`, `OrgLookup` and `OrgAuthorization` are already `ObjectProvider`-resolved ports that default-deny on absence; swapping their JPA implementations for HTTP ones is one class per port and zero schema change. Cutting `org_subscription.plan_id → plan` forces a fourth port, `PlanCatalog`, which is exactly the seam AGENTS §5.2 already names as the destiny.

### The five foreign keys that must be cut

17 FKs exist. Twelve are intra-tenant or intra-platform and travel cleanly. **Five cross the boundary:**

| constraint | edge | replacement |
|---|---|---|
| `membership_org_id_fkey` | `membership.org_id → organization(id)` | soft ref; the schema is the assertion |
| `org_role_org_id_fkey` | `org_role.org_id → organization(id)` | soft ref |
| `org_group_org_id_fkey` | `org_group.org_id → organization(id)` | soft ref |
| `org_subscription_plan_id_fkey` | `org_subscription.plan_id → plan(id)` | soft ref — cross-schema works while co-located, is **impossible** after hop 2 |
| `user_device_trust_device_id_fkey` | `user_device_trust.device_id → user_device(id)` **ON DELETE CASCADE** | the cascade is load-bearing per its own javadoc. Replace with the denormalization in §2.1 plus an event-driven delete plus a `PURGE_ORDER` case. **Forget this and revoked devices stay trusted forever.** |

**AGENTS §1's FK rule has a gap and this is it.** The rule is stated on the **module** axis ("no cross-module foreign keys"), and all five survivors are *intra-module* FKs that happen to cross the **tenant** axis: `plan` and `org_subscription` are both in `subscription/`; `user_device` and `user_device_trust` are both in `access/`. The rule structurally cannot catch them. It gains a second clause and a test.

### The extraction bundle, and the acceptance test

An extracted deployment needs exactly:
1. `pg_dump -n t_<hex>` — the 19 tenant tables plus the tenant half of the 7 split ones.
2. The org's `organization` and `external_organization` rows, seeded into the new deployment's own `platform` schema.
3. A **person projection** for every `person_id` referenced anywhere in the schema — copied, not moved.
4. A snapshot of `plan`, `plan_entitlement`, `sla_policy`, `translation`, `setting`, `feature_flag` (the org's `feature_flag_org_override` rows are already in the dump). Missing them is a boot-time failure with two opposite symptoms and no error: `EntitlementsImpl.limitOf` returns null when an entitlement is absent and `requireWithinLimit` does nothing when the limit is null → **every quota unlimited**; meanwhile `hasFeature` returns false → **every `requireFeature` 403s**.
5. The `impersonation_session` rows naming the org, so its audit rows can resolve their stated reason.
6. **Fresh empty** `event_publication`, `event_inbox`, `shedlock`, `idempotency_key`. Never copied.
7. The org's bytes from SeaweedFS under `doc/o/<orgId>/` and `exch/o/<orgId>/`.
8. **Re-minted** `api_key` rows; the platform revokes the old ones and permanently reserves their prefixes.
9. An IdP decision: federate to the platform issuer (nothing copied) or a new issuer with a re-link event per member (`external_identity` is rewritten, never copied).
10. `search_document` **rebuilt**, not copied.
11. `notification_delivery` **drained**, not copied. `webhook_delivery` and `exchange_job` are in the dump, so drain the PROCESSING rows before dumping or the same delivery runs on both sides.

**The acceptance test, one sentence:** stand the extracted deployment up with the platform **completely unreachable — no network route at all** — then log in as a member, list members, open a ticket, run an export, and let the nightly jobs run once. Anything that fails names a table on the wrong side of the boundary.

Write it as `TenantSchemaSelfContainmentTest` on Testcontainers (ADR 0003 — no H2, no fakes): (a) assert no FK crosses a schema boundary, (b) assert every tenant-tier table appears in the tenant schema and no platform-tier table does, reading the tier from `docs/DATA_MODEL.md`, and (c) actually perform the dump/restore. **Without (c) the design drifts the first time someone adds a table.**

---

## 7. The phased plan

Cheap, reversible groundwork first. No data moves before Phase 2; no schema is created before Phase 5; no database is split before Phase 7.

### Phase 0 — The boundary made real, with nothing routed

Deliverables: the tier assignment as **data** — a `tier` column in `docs/DATA_MODEL.md` for all 55 tables. Cut the five boundary FKs to soft refs. Denormalize `person_id` and `fingerprint` into `user_device_trust` + event-driven revocation cleanup + a `PURGE_ORDER` case. Add the four missing `org_id`-leading indexes (`notification_delivery`, `impersonation_session`, `feature_flag_org_override`, `signup_request`). Add a **multi-org person** to the seed fixture. Set `wal_level = logical` at the next maintenance window (needs a restart; needed in Phase 7; free to do now). Add the second clause to AGENTS §1's FK rule.

**Gate:** `./gradlew test` green. A test enumerates all 55 tables from `information_schema` and fails on any table without a tier in `DATA_MODEL.md`. `select count(*) from pg_constraint where contype='f'` shows **12**, and a test asserts no remaining FK connects a platform-tier table to a tenant-tier table. A test proves a revoked `user_device` leaves no live `user_device_trust` grant. `/me/organizations` has a test covering a person in two orgs. `SHOW wal_level` returns `logical`.

### Phase 1 — The routing mechanism, against one schema

Deliverables: `TenantContext` (three states, zero Spring deps, throws inside an active transaction). `TenantRoutingDataSource` set-on-borrow. Tenant pinning inside `CurrentUserFilter`, plus the route allowlist, plus `McpToolDispatcher.callOnCallerContext`. Create `ext`, `no_tenant`; `ALTER EXTENSION pg_trgm SET SCHEMA ext`. Every state still resolves to today's single schema except absent, which resolves to `no_tenant`. `ddl-auto: none` + the explicit post-ready validator. `spring.datasource.hikari.schema`.

**Gate:** with `maximum-pool-size: 1`, two tenants alternating requests each read only their own rows (the leak test). A request with no tenant and no allowlist match returns 403 in the envelope with a `requestId`. A test asserts `current_schema()` is `no_tenant` on a connection borrowed with `TenantContext` absent. `TenantContext.set()` inside `@Transactional` throws. Search still works: `word_similarity` and `gin_trgm_ops` resolve from `ext`. Full suite green.

### Phase 2 — The split

Deliverables: the generator that bisects V1–V52 into `db/migration/platform/` and `db/migration/tenant/` from the tier table. `platform` and `tenant_pool` schemas; `ALTER TABLE … SET SCHEMA` for all 55 (+7 duplicated). All platform tables explicitly qualified in entities and native SQL. `spring.modulith.events.jdbc.schema: platform`; ShedLock's `withTableName("platform.shedlock")`. The seven split tables' routing adapters. `platform.org_membership_index` written in the same transaction as `membership`. Split `OrgExportService` into `pg_dump`-shaped extraction and the redacted GDPR bundle (`EXCLUDED_COLUMNS` — `webhook_subscription.secret`, `api_key.secret_hash` — is the point, and a raw dump would ship both). `FlywayBaselineTest` qualified. Update `docs/DATA_MODEL.md`, `docs/guides/api-guide.html` (the `/api/v1/audit` semantics change) and `docs/guides/system-diagram.html`.

**Gate:** full suite green on real containers. `pg_dump -n tenant_pool --schema-only` contains exactly the 19 tenant tables + 7 split ones and none of the 29 platform ones. An ArchUnit test fails the build when an entity or a native query names a platform-tier table without the `platform.` qualifier. A test asserts `OutboxResubmissionJob` reads `platform.event_publication` with `TenantContext` set to a tenant. `./gradlew exportModulithDocs exportOpenApi` diffs committed.

### Phase 3 — Caches, jobs, and the queue signal

Deliverables: the cache registry (GLOBAL/TENANT declaration, `getCache` fails on undeclared, TENANT keys prefixed in L1 and L2, throws when absent), `person-by-subject` and `org-by-external-id` declared GLOBAL, the hand-rolled prefixes removed from `org-permissions`/`org-entitlements`, `SubscriptionService`'s `allEntries` evict fixed, `l1-max-size` raised. An axis declared on all 14 `@SchedulerLock` jobs, resumable cursors, re-derived `lockAtMostFor` and `RUN_DEADLINE` values. `platform.queue_signal` + the two-step claim for all three queues, with fairness.

**Gate:** a test enumerates every `cacheNames` value in the codebase and fails on an undeclared one. A test proves a TENANT cache lookup with `TenantContext` absent throws. A test reproduces the documented webhook outage — one tenant with a large backlog — and asserts other tenants still get delivered, which is a **new** guarantee the current code does not have. Every job has a declared axis, asserted by a test. Notification throughput measured before/after.

### Phase 4 — Per-tenant migrations and the registry

Deliverables: `platform.tenant_placement`. The migration runner (parallel, shared `resourceProvider` + `javaMigrationClassProvider`, never-abort, per-schema history, `repair` mode). A Kubernetes Job that runs to completion before the app rollout. `MIN_TENANT_SCHEMA_VERSION` + the 503 floor check in `CurrentUserFilter`. Tenant migrations forbidden from `executeInTransaction=false`.

**Gate:** V53 is authored as a tenant migration, the runner applies it to `tenant_pool`, and `tenant_placement.schema_version` reflects it. A deliberately failing tenant migration leaves that schema at the previous version with **no** history row, marks the registry `FAILED`, and does not stop the runner. A binary with a floor above a schema's version returns 503 + `Retry-After` for that tenant and serves every other tenant normally. A test asserts every tenant schema's `flyway_schema_history` head matches `tenant_pool`'s.

### Phase 5 — Promotion: the first silo

Deliverables: the promotion runbook and its automation — freeze (HTTP `RESTRICT` window **and** queue/job pause), create schema, migrate, copy, verify row counts and checksums, flip placement, evict caches, unfreeze. Reverse promotion. The silo-count ceiling enforced in the promoter. The bounded-fan-out form of `pageForQueue`, `lockBreached`, `SoftDeletePurgeJob`, and the retention jobs over `tenant_pool` + silos.

**Gate:** a real org promoted to `t_<hex>` and demoted back, with per-table row counts identical at both ends and a measured freeze window recorded in the runbook. The full suite passes with the test fixture running one org in a silo and the rest pooled. `SoftDeletePurgeJob` completes within its lease with a silo present. The promoter refuses at the ceiling.

### Phase 6 — Extraction rehearsal

Deliverables: the extraction bundler (the 11 items in §6). `TenantSchemaSelfContainmentTest` including the real dump/restore. The person-projection copier. The catalog snapshotter. The SeaweedFS prefix extractor.

**Gate:** `pg_dump -n t_<hex>` restores into an empty database seeded with a single-tenant `platform` schema; the jar boots against it **with the platform host unroutable**; a member logs in, lists members, opens a ticket, runs an export, and the nightly jobs run once. Any failure names a table on the wrong side of the boundary and blocks the gate.

### Phase 7 — Own database

Deliverables: `AbstractRoutingDataSource` over `tenant_placement.datasource_name`. Row-filtered logical replication cutover. `PersonLookup` / `OrgLookup` / `OrgAuthorization` HTTP adapters plus the new `PlanCatalog` port. Stale-while-unreachable for the two resolution caches, with a hard ceiling and **never** for `OrgAuthorization`. Documented as ADR 0011 — this hop changes the consistency model and deserves its own record.

**Gate:** one org served entirely from a second database, with the freeze window measured and under 60 s. A revoked membership denies the very next request across the split (the AGENTS §5.5 invariant, re-proved). Killing the platform service degrades the silo to cached identity for the stated ceiling and then denies — never serves stale authorization.

### Phase 8 — Own deployment, own service

Gateway route by `organization` claim; fresh infrastructure tables, Valkey prefix, and object-store root. Follows ADR 0007 and AGENTS §5.2; no new decisions needed here that this document has not already made.

---

## 8. Open questions

Each has a recommended default, so silence is safe.

**Q1. What is the silo ceiling per database, and is it enforced?**
The measured constraints are ~0.5–0.66 ms of planning per UNION branch (not amortized) and 2.004 lock entries per branch against ~6,400 cluster-wide slots. **Default: 200 silos per database, enforced in the promoter, with the derivation written into the ADR.** At 200 the platform support query costs ~130 ms of planning and ~403 locks per fan-out transaction. **This is the one number in the document I would most want re-measured before it is load-bearing** — the experiment is to replay the UNION benchmark at N = 50 / 100 / 200 against real `tenant_pool` + silo data, not synthetic schemas.

**Q2. Do we build `platform.ticket_index` and `platform.audit_index` now, or on a trigger?**
**Default: on a trigger, not now.** `ticket_index` at 50 silos; `audit_index` never — `/api/v1/audit` without `?org=` returns platform-scope rows, documented. Every projection is duplicated data with its own drift, and **no projection ships without its reconciler**, modelled on `sweepSearchResidue`, which exists precisely because a projection with no delete path leaves residue forever.

**Q3. Who promotes, and on what trigger?**
**Default: operator-initiated only, from a runbook.** Automatic promotion by size or IOPS share only after ten successful manual promotions, and never without the freeze mechanism proving itself at the real data volume.

**Q4. `api_key`: whole in platform, or the two-probe split?**
**Default: whole in platform** (§2.1). The owner may overrule if "the secret hash never leaves the tenant" is worth an index probe on every machine request; the counter-argument is that the extraction bundle re-mints keys anyway, so nothing travels either way.

**Q5. Non-transactional tenant migrations: forbidden, or allowed with sign-off?**
**Default: forbidden.** A silo's tables are orders of magnitude smaller than today's shared ones, so AGENTS §4.6's own argument covers essentially every case, and the failure mode multiplies by silo count and converts a self-healing failure into a manual one.

**Q6. Does the tenant-migration Job block the app rollout?**
**Default: block — Job to completion, then rollout.** The version-skew rule puts the code behind the schema; running them concurrently makes the 503 floor check load-bearing in production rather than a backstop, which is a much worse place to discover a bug in it.

**Q7. A tenant stuck `FAILED` after three fan-out attempts?**
**Default: serve it at its old version if the binary tolerates it (that is what the floor is for), page if it falls below the floor.** Silent indefinite 503 is the one outcome to design out.

**Q8. Post-extraction identity: federate, or own issuer?**
**Default: federate first, own issuer as the eventual full split** — and write both stages into the ADR so nobody mistakes stage one for the end state.

---

### Genuinely unknown, with the experiment that settles each

1. **Are `document.storage_key` values org-namespaced in SeaweedFS?** `uq_document_storage_key` is globally unique so keys do not collide *today*, and `OrgDocumentController:62` mints `doc/o/<orgId>/…` while `ArtifactStore:52` mints `exch/o/<orgId>/…` — but `PersonalDocumentController:45` mints `doc/u/<personId>/…`, which is not org-partitioned, and I have not read the key-minting code in the `files` module itself. **Experiment:** read `files`' key minter; assert every org-scoped key carries the `o/<orgId>/` prefix; add a test. If keys are ever flat, two extracted tenants can collide after a key is freed by a soft-delete purge.
2. **Whether the multi-org person path works at all.** Zero of 193,940 seeded people belong to more than one org. Every fan-out claim in this document is untested by the fixture. **Experiment:** the Phase 0 seed change, then run the full suite.
3. **Whether pinning the Modulith registry actually holds under every borrow order.** `spring.modulith.events.jdbc.schema` is a real supported property (verified in `spring-modulith-events-jdbc-2.1.0.jar`'s configuration metadata), but a per-request `search_path` swap plus an `@Async` listener plus the resubmitter is three code paths. **Experiment:** the Phase 2 gate test — run the outbox resubmitter with `TenantContext` set to a tenant and assert it read `platform.event_publication`.
4. **Real notification throughput under `queue_signal`.** Claiming one tenant per batch drops the effective batch from 200 to that tenant's share (~8 at today's distribution), so the round-trip count per second rises. **Experiment:** the Phase 3 gate — measure delivered-per-second before and after against the current seed, and again with one tenant holding 90% of the backlog.