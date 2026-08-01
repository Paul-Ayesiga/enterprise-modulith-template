# Code audit — 2026-08-01

**Scope:** all of `src/main`, `src/main/resources` (yaml + all migrations), and the test suite, judged
against [AGENTS.md](../../AGENTS.md) (§ references below are to it). **Method:** five parallel
reviewers — identity+organization, shared kernel, webhooks+notification+audit+scheduler,
files+analytics+settings+resources, and a repo-wide pattern sweep — each required to quote file:line
evidence, to check for a documented deliberate trade-off before flagging anything, and to skip what
ArchUnit/contract tests already enforce. **Baseline:** the full suite is green on the current tree
(Gradle-verified: the `test` task's input hash matches a green execution).

**This is also the fix plan** (per the plan-first workflow): nothing below has been changed yet
except three documentation-drift items SRS §9.3 already tracked. Approve a phase and it gets
implemented with a test that fails without each fix, then the full suite.

**Headline:** adherence to the repo's own (unusually demanding) standard is high. Verified clean:
module boundaries and ports, both authorization axes with no role bypass, all five impersonation
invariants, subject-not-username keying everywhere durable, soft-delete annotations + purge order,
queue claim/fence/reclaim/backoff/dead-letter, no SQL reachable from user input, no entity setters,
no mocked repositories/H2, no `@Scheduled` without `@SchedulerLock`, logging discipline, zero
TODO/FIXME debt. The defects cluster in: cache/idempotency edge cases in `shared`, missing retention
for two tables, controllers that quietly skip the service layer, unbounded background reads, and
config that bypasses the properties-record discipline.

---

## HIGH

### H1 — File uploads over 1 MB are rejected; the multipart path is dead code over HTTP
`src/main/resources/application.yaml` (missing block) + `files/internal/FileController.java:42,69`.
There is no `spring.servlet.multipart.*` configuration anywhere, so Boot's default
`max-file-size=1MB` applies — every real upload > 1 MB gets a 413 before the handler runs, and the
controller's `MULTIPART_THRESHOLD_BYTES = 5 MB` branch (`storage.putLarge`) is unreachable over
HTTP. The suite can't see it: `FileApiTest` uses byte-sized `MockMultipartFile`s (MockMvc bypasses
the resolver limit) and `FileStorageIntegrationTest` calls `putLarge` directly, never over HTTP.
SRS §9.2 already flags the missing config as a gap; the dead-code consequence is new.
**Fix:** deliberate `spring.servlet.multipart.max-file-size`/`max-request-size` (`${ENV:default}`,
why-comment, above the 5 MB threshold) + one HTTP test uploading past the threshold.

## MEDIUM — correctness

### M1 — Cached nulls poison the L2 cache round-trip
`shared/cache/CacheConfig.java:37-43,62-65`. The Valkey serializer never enables
`enableSpringCacheNullValueSupport()` (default false — verified against spring-data-redis 4.1.0
sources) and the type validator does not allow `org.springframework.cache.support.NullValue`. A
cached null (e.g. `SettingService.valueOf` — the documented hot path — returns `orElse(null)`)
serializes as `NullValue`, then every read throws `InvalidTypeIdException` → WARN, treated as a
miss → DB hit → re-`put` → broadcast evicts peers' good L1 entries → repeat. Negative caching is
broken *and* self-churning. No cache test covers a null value.
**Fix:** `.enableSpringCacheNullValueSupport()` + allow `NullValue` in the validator (or
`disableCachingNullValues()` and document that misses aren't negatively cached) + a null-value test.

### M2 — `TwoLevelCache.evict()` clears L1 before L2: a concurrent reader resurrects the stale entry
`shared/cache/TwoLevelCache.java:106-124` vs the L1-refill at `:57-59`. Interleave: writer evicts
L1 → reader misses L1, reads stale L2 → writer evicts L2 + broadcasts (the listener skips the
writer's own instance id) → reader puts stale value into L1. On the `org-permissions` cache this
voids the stated "suspension plus its cache eviction is immediate" guarantee for up to the 60s L1
TTL, on the writer's own instance, with a perfectly healthy L2. ADR 0004 documents bounded staleness
only for a *failed* L2 evict.
**Fix:** evict L2 before L1; state the residual in-flight-read bound in ADR 0004.

### M3 — Idempotency `complete()`/`release()` are unfenced against the lease takeover
`shared/idempotency/IdempotencyStore.java:61-73`. The claim has a PT5M takeover lease (ADR 0005),
but a stale claimant that outlives it can still `release` (DELETE) the new claimant's in-progress
row — letting a third duplicate re-execute the side effect — or `complete` over the new claimant's
stored response. §7 mandates exactly this fence for the delivery queues ("fence every status update
… so a stale claimant cannot corrupt the new owner's state"); the rule wasn't applied to the
idempotency store itself.
**Fix:** return `created_at` from `claim()` and add `and created_at = ?` to both statements.

### M4 — A webhook delivered successfully can be re-POSTed or recorded FAILED
`webhooks/internal/WebhookDeliveryWorker.java:134-135`. `sender.send` and `queue.markDelivered` sit
in one `try`: if the send succeeded but the status write throws (DB blip), the catch reschedules
(→ duplicate POST) or dead-letters (→ delivered webhook recorded FAILED with a DB error as
`last_error`). `NotificationDeliveryWorker.java:222-229` handles this exact case separately and
documents why — the reference queue lacks the fix its sibling has. The worker's javadoc also
overclaims "no double-sends"; delivery is at-least-once (crash between send and mark → stale
reclaim → re-POST) and nothing in the module says so.
**Fix:** mark-failure leaves the row PROCESSING for the stale-lock reclaim (mirror the notification
worker); correct the javadoc; note that receivers dedupe on the delivery id header.

### M5 — Disabling a webhook subscription does not stop already-queued deliveries
`webhooks/internal/WebhookDeliveryQueue.java:80` + `WebhookSubscriptionService.update:73-83`. The
claim join checks `s.deleted_at is null` but not `s.status = 'ACTIVE'`, and `update` to DISABLED
never cancels outstanding rows — queued deliveries keep being POSTed (with backoff, for hours) to an
endpoint the tenant just disabled. The service's own DELETE javadoc argues revocation "must stop
everything already queued"; the same argument applies to the softer revocation the API offers.
No test covers DISABLED-with-queued-deliveries.
**Fix:** add the status predicate to the claim (PENDING rows resume on re-enable) or cancel/park on
disable — and document the chosen semantic.

### M6 / M7 — Two tables grow without bound: `webhook_delivery` and `event_inbox`
`WebhookDeliveryQueue.purgeDeliveredBefore:127` has **no caller** (and no `app.webhooks.retention`
property), despite AGENTS §4.2, the V17 header and two javadocs all claiming retention trims it —
DATA_MODEL §7.3 already triages it "Unintended". `EventInbox` (`shared/events/EventInbox.java`) has
no purge path at all; §6 makes every side-effecting listener insert into it forever, and dedup only
needs to cover the redelivery window. Both are in SRS §9.2; neither is a documented trade-off.
Related: FAILED (dead-letter) rows are purged in *neither* queue and no comment says they're kept
deliberately.
**Fix:** one scheduled, ShedLock-guarded retention job (per §7's purge discipline) covering both
tables + a decision on dead-letter retention.

### M8 — Failed S3 multipart uploads leak their parts forever
`files/internal/S3StorageProvider.java:67-99`. No failure path calls `abortMultipartUpload`, and no
bucket lifecycle rule exists — every interrupted large upload leaves its parts stored (and billed)
under the open upload id indefinitely.
**Fix:** best-effort abort in the catch before rethrowing.

### M9 — The S3 client has no explicit timeouts
`files/internal/S3ClientConfig.java:24-33`. §7: "Timeouts are mandatory on anything remote" — every
other remote in the repo pins them with a why-comment (Lettuce 2s, SMTP 5s, webhooks 5s). Apache
defaults leave a stalled SeaweedFS holding request threads ~30s per attempt, and the `storage`
breaker has no slow-call config, so slow-but-completing calls never open it.
**Fix:** connection/socket timeouts + `apiCallTimeout` as commented `app.storage.*` properties.

### M10 — Concurrent runs of the same analytics report race on one staging table
`analytics/internal/DuckDbAnalyticsEngine.java:115-143` + `AnalyticsReportService.run:23`.
Materialization happens per request on separate autocommit connections; two concurrent runs of the
same report share `mart_x__staging` — B's `DROP` discards A's half-inserted rows, then A's remaining
batches either 500 or land in B's table and get swapped in as duplicated mart rows. The javadoc
covers refresh-vs-read, not refresh-vs-refresh.
**Fix:** serialize per mart, or a unique per-run staging name dropped after the swap.

### M11 — Concurrent same-email provisioning surfaces as a 500
`identity/internal/KeycloakUserAdminGateway.java:53-58`. Two concurrent invites both miss
`findByEmail`, both `POST /users`; Keycloak's 409 is unmapped → `INTERNAL_ERROR`. The repo's own
precedents handle both halves of this race class (`KeycloakOrgAdminGateway` maps the KC 409 with a
comment; `saveLocalUser` resolves the local duplicate to the winner) — §4.4's "catch only where
losing the race has a well-defined idempotent outcome" applies: re-lookup by email and reuse.

### M12 — A created user/org and the audit row that explains it commit separately
`identity/internal/UserProvisioningService.java:61-62`, `organization/internal/OrganizationService.java:53-55`.
A crash between the state commit and `auditLog.record` leaves a row no audit accounts for — and the
idempotent retry can never write it (`alreadyProvisioned` skips; org re-create 409s by design). The
repo's own bar is `IdentityReconciliationJob.disableOne`'s javadoc: the row change and the audit row
"commit together".
**Fix:** one `TransactionTemplate` block around save+record; record `organization.created` inside
`projectWithOwner`'s transaction.

### M13 — Nightly identity reconciliation can starve its own tail
`identity/internal/IdentityReconciliationJob.java:81,89` + `UserRepository.java:24`. The candidate
query is unbounded and unordered; `subList(0, batchSize)` then keeps whatever Postgres returned
first — PRESENT rows remain candidates forever, so with > batch-size users nothing guarantees the
tail is ever examined, and the whole non-disabled user base is hydrated nightly to inspect ≤ 500.
Also: per-row failures are logged but the run never rethrows at the end (§7's "loud AND complete"
rule, which `SoftDeletePurgeJob` implements), and 500 × 15s read-timeout worst case exceeds the
PT30M lease.
**Fix:** ordered keyset batches with a pushdown `Limit`, first-failure rethrow after the loop, and a
wall-clock bound inside the lease.

### M14 — Raw `S3Exception` escapes the files module
`files/internal/S3StorageProvider.java:133-135` (`delete`), `:104-113` (`get` non-404),
`createMultipartUpload` outside its try. §2.3's stated contract for this exact port: "S3 SDK types
never escape the module" — and the wrapping is half-applied (`put`/`exists` wrap, `delete`/`get`
don't).
**Fix:** wrap the remaining paths in `FileStorageException`.

## MEDIUM — standards / structure

### M15 — Five controllers bypass the service layer (repository/Jdbc access, no transaction boundary)
§3.1 ("no repository access") + §4.3 (the boundary is a service method, `readOnly = true` on reads):
`audit/internal/AuditController.java:36` (injects the repository *and* assembles Specifications),
`identity/internal/UserAdminController.java:20`, `identity/internal/MeController.java:17`,
`organization/internal/MemberController.java:37` (second repository for response enrichment),
`scheduler/internal/SchedulerController.java:24` (`JdbcTemplate` — tolerable for a framework table,
but undocumented). The other eleven controllers all delegate. No test enforces the rule.
**Fix:** thin package-private query services; `MemberService` returns the role code it already
resolves.

### M16 — Two tenant-growable collections are unpaginated bare Lists
`organization/internal/RoleController.java:61`, `webhooks/internal/WebhookController.java:67`.
§1 hard rule / §3.3: collections paginate by cursor. Roles and webhook subscriptions are
tenant-created with no per-org cap anywhere — bounded by practice, not by nature, and no comment
claims the exemption (the enum/job-count catalogs are the legitimate analogs; the same controllers'
sibling endpoints paginate correctly).
**Fix:** `WindowedResult` + the house `createdAt desc, id desc` sort — or a real, documented cap.

### M17 — Leaked mutable state
`organization/internal/Role.java:143-145`: `getPermissions()` returns the live Hibernate-managed
set — mutating it bypasses `requireEditable()` *and* the `RolePermissionsChanged` event, and
Hibernate dirty-checking flushes the change silently with no audit row and stale caches.
`shared/cache/TwoLevelCache.java:57-59,160-174`: values refilled from L2 are shared mutable
collections even when the producer cached an unmodifiable one (`PermissionResolver`) — one caller's
`.add()` poisons every later authorization check on that instance.
`TwoLevelCacheManager.getCacheNames()` returns the live keySet view (supports `remove()`).
§12: unmodifiable views on anything returned.
**Fix:** `Set.copyOf` in the entity getter; re-wrap collections on L2 refill; copy the name set.

### M18 — `app.idempotency.*` / `app.scheduler.*` exist only as scattered `@Value` defaults
`shared/idempotency/IdempotencyFilter.java:54-55`, `scheduler/internal/IdempotencyPurgeJob.java:22`,
`EventPublicationPurgeJob.java:25`. §8: settings live under `app.` in yaml, bound to validated
properties records. These families are absent from application.yaml (invisible for the K8s
migration) and unvalidated — a negative `app.idempotency.retention` purges every key with no startup
guard, exactly what `SoftDeleteProperties`' compact constructor exists to prevent. (The one
sanctioned `@Value` exception, `ImpersonationFilter`, carries its why-comment; these don't.)
**Fix:** `IdempotencyProperties` + a scheduler retention record, validated, keys added to yaml.

### M19 — The notification retention purge is hand-rolled inside the poll loop
`notification/internal/NotificationDeliveryWorker.java:239-252`. Runs on every instance (no
ShedLock — §7: "never acceptable"), as one unbounded DELETE on the poller thread (a big backlog
stalls claiming for its duration), with failures swallowed to WARN. All three violate §7's purge
discipline; `SoftDeletePurgeJob` is the in-repo model.
**Fix:** move to a scheduled, locked, batched job — the natural home is the same job that fixes
M6/M7.

### M20 — Settings-module internals are public because tests sit outside the package
`settings/internal/SettingService.java:15` (+ `FeatureFlagService`, `Setting`, `FeatureFlag`).
§2.1: package-private unless something forces it, "and say why". The stated why ("hot read path for
other modules") is architecturally impossible — no port exists and Modulith would fail the import.
The real force: tests placed in `ug.co.smsone.settings` instead of `.internal`, and
`AnalyticsIntegrationTest` even imports `settings.internal.SettingService` **across a module
boundary** from the analytics test tree — a §1 boundary violation the main-source checks cannot see.
Same pattern, milder: `WebhookDeliveryWorker`, `NotificationDeliveryWorker`,
`EventPublicationPurgeJob`, and three public `@ConfigurationProperties` records
(`AnalyticsProperties`, `StorageProperties`, `NotificationProperties`).
**Fix:** move the tests into the internal packages (as organization does), seed cross-module test
data via `JdbcTemplate`, then demote.

### M21 — Startup runs `findAll()` over every organization
`organization/internal/SystemRoleCatalogReconciler.java:30`. The org table is unbounded in a
multi-tenant system; every instance start materializes all rows and runs sequential per-org seeding
before readiness. The javadoc documents per-org failure isolation, not the unbounded load.
**Fix:** keyset pages / bounded stream.

### M22 — Workers persist `Instant.now()` instead of the injected `Clock`, mixing time sources
`webhooks/internal/WebhookDeliveryWorker.java:146`, `notification/internal/NotificationDeliveryWorker.java:200,217`.
§7: injected `Clock` for anything persisted or asserted. `next_attempt_at` is persisted, and the
claim predicate compares it against DB `now()` — app/DB skew silently stretches or shrinks backoff,
and tests must fast-forward via raw SQL because worker time is uncontrollable.
**Fix:** inject `Clock` (or compute the stamp in SQL like every other stamp in those queues).

### M23 — Member-list page has no supporting index; role permissions fetched 1+N on that path
`db/migration/V11__organization_rbac.sql:59-60`: nothing serves
`where org_id = ? order by created_at desc, id desc` (the V18 impersonation index is the in-repo
precedent for exactly this shape). `organization/internal/Role.java:53`: the EAGER
`@ElementCollection` is the right call for authorization resolves (bounded enum set) but is
uncommented despite being load-bearing (`open-in-view: false` — LAZY would throw in
`MemberService.invite`), and `MemberController.roleCodesFor` pays one `role_permission` select per
role on every member page just to map id → code.
**Fix (V20):** `idx_membership_org_recent (org_id, created_at desc, id desc) where deleted_at is
null` with the which-query comment; a code-only projection for the id→code map; the why-comment on
EAGER. Same migration can pick up: an expression index for the case-insensitive `app_user` email
lookup (the existing `idx_app_user_email` btree cannot serve `upper(email) = upper(?)` and serves no
query today, violating §4.5's own comment rule), and — optional — `audit_log (occurred_at)` for the
range filter.

---

## LOW (grouped; file:line in parentheses)

**Idempotency & envelope edges.** Request hash omits the query string, so same-key/different-query
replays instead of 409ing (`IdempotencyFilter.java:88`); response buffering is uncapped and
round-trips bytes through a UTF-8 String — latent corruption for any future binary/large body
(`:99,139-146`); `EnvelopeErrorWriter` writes JSON without setting UTF-8, defaulting to ISO-8859-1
(`:45-46`); `GlobalExceptionHandler` maps breaker-open (`CallNotPermittedException`) to a
stack-traced 500 instead of a quiet 503 — an outage becomes indistinguishable from a crash
(`:119-126`); framework-raised 413/429 render `code=BAD_REQUEST` (already documented in SRS §4.2).

**Cursor & cache edges.** `Cursors` doesn't escape `|`/`=` in String key values — the first
name-sorted collection mints a `links.next` its own decode 422s (`Cursors.java:42-46`);
`TwoLevelCache.get(key, Callable)` doesn't serialize loads, so `@Cacheable(sync = true)` would
silently not sync (`:75-87`); invalidation messages decode with the platform default charset
(`CacheConfig.java:93`); `DistributedRateLimiter` rebuilds Bandwidth/config/proxy per request for a
fixed tier set (`:72,162-168`) and uses the non-wraparound-safe `nanoTime` comparison idiom
(`:64,89,123`).

**Security-adjacent.** Swagger UI + full OpenAPI are `permitAll` unconditionally with no gate flag
and no stated acceptance (`SecurityConfig.java:37-38`); `SafeOutboundUrl` unwraps NAT64-embedded
IPv4 but not 6to4 (`2002::/16`) or Teredo — drift, not decision (`:74-77`); both outbound
`HttpClient`s rely on the JDK's default `Redirect.NEVER` with no comment or test pinning the
load-bearing default (`WebhookSender.java:24-26`, `HttpChannels.java:17-19`);
`ImpersonationService.end` answers 403-if-exists/404-if-not to non-admin callers, disclosing another
operator's session id validity (`:138-143`).

**Consistency & polish.** Javadoc drift: `SchedulerController` and `AnalyticsReportController` say
"admin-only" over a `platform-support` floor; `AuditController` advertises `occurredFrom/To` params
that are actually `from`/`to`. No-op writes re-publish and re-audit: `Setting.change`/
`FeatureFlag` on identical values, `OrganizationService` suspend/rename/reactivate audit
`x → x` transitions — against the repo's own idempotent-early-return discipline.
`MembershipStatus.SUSPENDED` is a dead state nothing can enter. Read paths missing
`@Transactional(readOnly = true)`: `MemberService.list`, `RoleService.list/require`,
`OrganizationService.require`. Inline resource-type literals `"user"` (declared independently in two
files) and `"file-presign"` instead of `RESOURCE_TYPE` constants. `WebhookDispatcher` re-serializes
the identical payload once per subscription (`:38-39`). `ImpersonationLookupImpl` fetches the
target's row twice per impersonated request (`:51,84`). File `WebhookDeliveryRecords.java` declares
only `NewWebhookDelivery`. `OPENAPI_*` `@Value` keys bypass the `app.*`-in-yaml convention;
`FileController`'s presign TTL and multipart threshold are hardcoded tuned numbers;
`POSTGRES_PASSWORD` and `S3_*` dev defaults lack the mandated "ALWAYS override in production"
comment; client-supplied content types are stored and replayed verbatim on download (mitigated by
the storage-origin 302). Two tests hand-roll sleep/poll loops where siblings use Awaitility
(`EventPurgeJobIntegrationTest.java:48`, `ValkeyCacheIntegrationTest.java:124`).

**Documented deferrals (not defects).** `SettingChanged` lacks `occurredAt` — EVENTS.md explicitly
defers it until the event's first consumer; do it then (it also needs the stamp on delete, see SRS
§9.2's delete-route row). Settings/flag GET endpoints being open to any authenticated user, the
unreachable delete/restore paths, plaintext webhook secrets, no CORS, SMS stub-on-by-default: all
already tracked in SRS §9.2 — kept there, not re-litigated here.

---

## Fix plan (for approval — each phase lands with tests that fail without it, then the full suite)

| Phase | Contents | Risk |
|---|---|---|
| **1. Correctness, small diffs** | H1 multipart config+test · M1 null-value support · M2 evict order · M3 idempotency fence (+query-string hash) · M4 webhook mark isolation · M5 claim status predicate · M8 multipart abort · M9 S3 timeouts · M11 KC 409 mapping · M17 defensive copies | Low — each is a localized change with an obvious failing test |
| **2. Jobs & retention** | M6/M7/M19 one scheduled locked retention job (webhook_delivery, event_inbox, notification SENT) · M13 reconciliation ordering/limit/rethrow · M22 Clock injection · M12 audit atomicity | Low-medium — new job + property records |
| **3. Structure** | M15 controller→service extractions · M16 paginate roles/webhooks (additive: keep list shape via WindowedResult) · M18 properties records · M20 test relocation + visibility demotions · M21 bounded reconciler · M23 V20 indexes | Medium — mechanical but wide; no behavior change intended, suite is the gate |
| **4. LOW sweep** | grouped LOWs above, in file-cluster batches | Low |

M10 (DuckDB staging race) can ride in phase 1 (unique staging name) or 3 (per-mart lock) — proposer's
choice at implementation time.

---

## Remediation outcome (2026-08-01, same day — phases 1 & 2)

Implemented and green on the full suite (real containers; 290 tests at the time of the run). The
per-item ledger, each with its pinning test where one was practical, is in
[../CHECKLIST.md](../CHECKLIST.md) under *Audit remediation — phases 1 & 2*. Notes:

- **H1, M1–M14, M17 (defensive copies), M18, M19, M22** and the V20 indexes from M23: **fixed.**
- **M10** took the phase-1 shape (unique per-run staging name + orphan cleanup), not the per-mart lock.
- Pulled-forward LOWs: redirect policy pinned `NEVER` (webhooks + notification HTTP clients),
  idempotency hash includes the query string, fan-out serializes the payload once, invalidation
  listener decodes UTF-8, secret defaults carry the "ALWAYS override" comment, `getCacheNames`
  returns a copy, reconciliation rethrows after isolating per-row failures.
- **Two verification corrections along the way:** the first suite run failed because the new
  retention validation rejected `PT0S`, which `EventPurgeJobIntegrationTest` uses deliberately —
  aligned to the house precedent (negative-only fatal, zero legitimate). And the claim that a
  dead-letter purge "matches the webhook queue" now goes both ways: both queues purge terminal rows.

**Phases 3 & 4 (2026-08-01, later the same day): implemented.**

- **M15** — `AuditController` delegates to a new `AuditQueryService` (readOnly tx); `MeController`
  and `UserAdminController` delegate to `UserAccessService`, which now owns the module's app_user
  reads; `MemberController`'s role-code map moved into `MemberService` backed by **M23**'s id→code
  projection (killing the 1+N on the member page); `SchedulerController` keeps its inline framework
  read with the why-comment the finding asked for.
- **M16** — role and webhook-subscription listings are cursor-paginated (`WindowedResult`, house
  sort, readOnly service methods). Additive on the wire: `data` stays an array, `meta.page` +
  `links.next` appear.
- **M20** — settings tests moved into `settings.internal`, the event-purge test into
  `scheduler.internal`; `FeatureFlag`, `FeatureFlagService`, `EventPublicationPurgeJob`,
  `NotificationProperties`, `AnalyticsProperties` demoted package-private. `Setting` and
  `SettingService` stay public **with the why in their javadoc**: five shared-kernel ITs use them as
  the house guinea pig (soft-delete, audit capture, both cache tests, purge, analytics seeding).
- **M21** — the startup catalog reconciler walks organizations in keyset pages, not `findAll()`.
- **Phase 4 LOWs, done:** breaker-open → quiet `503 SERVICE_UNAVAILABLE` (new additive code) and
  `mapStatus` branches for 413/429/503 (SRS §4.2 caveat retired); `EnvelopeErrorWriter` writes
  UTF-8; `DistributedRateLimiter` uses the wraparound-safe nanoTime idiom and memoizes bucket
  configs; `Cursors` percent-escapes String values (+ round-trip test); `SafeOutboundUrl` unwraps
  6to4 and Teredo embeddings (+ tests); `Setting`/`FeatureFlag`/org rename/suspend/reactivate are
  idempotent no-ops (no x→x audit rows, no re-notifying admins — flag creation restructured so the
  guard can't swallow the creation event); dead `MembershipStatus.SUSPENDED` removed;
  `ImpersonationService.end` answers 404 (not 403) to a non-admin probing another operator's id;
  `ImpersonationLookupImpl` reads the target once per request; `FileController`'s presign TTL and
  multipart threshold live in `StorageProperties`, `file-presign` is a constant, and the
  content-type replay carries its trade-off comment; javadoc floors corrected (scheduler, analytics)
  and the analytics catalog's bounded-List exemption stated; Swagger/API-docs exposure documented as
  an accepted trade-off in `SecurityConfig`; `WebhookDeliveryRecords.java` renamed to
  `NewWebhookDelivery.java`; the two hand-rolled sleep/poll test loops use Awaitility.

**Deliberately untouched:** everything in `OpenApiConfig` (active concurrent work — including its
`OPENAPI_*` `@Value` keys), `SettingChanged.occurredAt` (documented deferral until its first
consumer), idempotency response-buffer bounding, and `@Cacheable(sync = true)` support (documented
unsupported instead).
