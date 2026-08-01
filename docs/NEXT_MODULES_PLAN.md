# Next Modules — Implementation Plan

> Localization → Search → Document → Exchange → Observability (user-set order, 2026-08-01; the
> user's call that **document and import/export are different modules that relate somewhere** is
> the shape of §3/§4: `document` is the business-facing catalog of managed files, `exchange` is
> the domain-agnostic import/export platform, and they meet where exchange registers its
> artifacts — source files, error reports, export results — as documents).
> Companion to [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) (platform pins) and
> [reusable-data-exchange-platform-guidelines.md](reusable-data-exchange-platform-guidelines.md)
> (the exchange module's principles). Every module follows AGENTS.md §2.4's recipe: package-info,
> API package of ports/events/records, `internal/` for everything else, its own migration, an
> integration test on real containers, regenerated docs. **No new infrastructure** — Postgres 18,
> Valkey, SeaweedFS and DuckDB are the whole toolbox (ADR 0001).
>
> Migration numbering from here: **V21 localization, V22 search, V23 document, V24 exchange.**
> Each phase lands only with tests that pin its gate, then the full suite.

---

## 1. `localization`

**What it owns:** the translation catalog and message resolution. **What it deliberately does
not:** deciding which user-visible strings exist — callers bring keys.

- **Model.** `translation` table (V21): `locale varchar(35)` (BCP-47), `msg_key varchar(200)`,
  `value text`, soft-deletable (editable config, like `setting`), partial unique
  `(locale, msg_key) where deleted_at is null`, listing index `(created_at desc, id desc)`.
  Added to `PURGE_ORDER`.
- **API package.** Port `Messages` — `resolve(key, locale, args…)` with fallback chain
  `exact locale → language → default locale → the key itself` (a missing translation renders the
  key, never throws); event `TranslationChanged(locale, key, occurredAt)`.
- **Resolution + caching.** Requested locale comes from `Accept-Language` (RFC 5646 ranges) via a
  small resolver in the module; resolved bundles cached per `(locale)` in the two-level cache,
  writers evict + broadcast (ADR 0004). Unknown locale falls through, never errors.
- **REST.** `GET/PUT/DELETE /api/v1/translations/{locale}/{key}` + cursor-paginated
  `GET /api/v1/translations?locale=` — writes `platform-admin`, reads any authenticated user
  (they are the UI's own strings). Envelope, `WindowedResult`, house sort.
- **First consumer.** `notification` templates resolve subject/body keys through the port
  (`FeatureFlagChangeNotifier` keeps literals until a template slice lands — port first, adoption
  second).
- **Deliberately out (slice B, separate approval):** localizing the error envelope's `detail` —
  it touches `GlobalExceptionHandler`/`ErrorCode` contract text and deserves its own pass.
- **Gate:** resolve falls back exactly as specified (IT matrix incl. `de-CH → de → default → key`);
  a PUT is visible via the port after cache eviction on a second "instance" (the ValkeyCache test
  pattern); suite green.

## 2. `search` — lightning fast, on Postgres

**What it owns:** a module-local search projection and the query surface. **Not** a new engine:
FTS is Postgres (`tsvector` + GIN) with `pg_trgm` for prefix/typo matching — zero new containers,
and honest speed: the gate MEASURES it.

- **Model (V22).** `search_document`: `org_id uuid` (tenant key, soft ref), `entity_type
  varchar(40)`, `entity_id varchar(64)`, `title`, `body text`, `tsv tsvector` **generated column**
  (`to_tsvector('simple', title || ' ' || body)`), `updated_at`. Unique `(entity_type, entity_id)`;
  `GIN (tsv)`; `GIN (title gin_trgm_ops)` for prefix/fuzzy; `(org_id)` btree for the tenant cut.
  `create extension if not exists pg_trgm` in the migration, header explaining the choice.
- **Feeding it.** Search compile-depends on other modules' **API events only** (legal direction):
  `@ApplicationModuleListener`s upsert projections for `organization` events (org registered/
  renamed, members) and `identity` (`UserProvisioned`) — each idempotent via `EventInbox`
  (message id = business identity), upsert `on conflict (entity_type, entity_id) do update`.
  For modules that want in: API port `SearchIndex { upsert(SearchDoc), remove(type, id) }` so a
  future module pushes without search learning its domain.
- **Query.** `GET /api/v1/search?q=…&type=…` — org-scoped
  (`hasPermission(#orgId… 'org:read')` via the caller's active org; platform variant
  `/api/v1/admin/search` at `platform-support` across orgs). `websearch_to_tsquery('simple', q)`
  ranked by `ts_rank_cd`, trigram fallback when the tsquery yields nothing (short/typo'd input).
  Tenant filter in the query, never after (§5.6).
- **Pagination.** Keyset over `(rank, id)`; `Cursors` gains a `d:` (double) tag — additive codec
  change, covered by the escaping test's round-trip pattern.
- **Gate — "lightning fast" is a number, not an adjective:** an IT seeds **100k** documents (bulk
  `JdbcTemplate` batches), runs 50 warm queries, and asserts **p95 < 50 ms** on the shared
  Postgres container (comfortably held by GIN at this size; the test prints the measured number).
  Plus: cross-org isolation (a hit in org A never surfaces for org B), event-driven upsert lands
  a searchable doc, redelivery doesn't duplicate.

## 3. `document` — the managed-document catalog

**What it owns:** the business record OF a stored file — name, type, size, who it belongs to,
which org, where it came from — over keys held by the `files` module (§2.3: storage stays behind
`FileStorageProvider`; `document` never sees an S3 type). **What it deliberately does not:** the
bytes (that is `files`) and moving data between systems (that is `exchange`).

- **Model (V23).** `document`: `org_id uuid` null for personal docs, `owner_subject varchar(64)`,
  `storage_key` (unique partial, soft ref into the files namespace), `name`, `content_type`,
  `size_bytes`, `source varchar(20)` (`UPLOAD | EXCHANGE`), soft-deletable + `PURGE_ORDER`,
  listing index `(org_id, created_at desc, id desc) where deleted_at is null`.
- **API package.** Port `Documents { register(NewDocument) → id }` — how `exchange` (and any
  future producer) files its artifacts without owning a catalog; event
  `DocumentRegistered(orgId, documentId, name, occurredAt)`.
- **REST.** `POST /api/v1/documents` (multipart → `files` put + row, or register a
  presigned-uploaded key); cursor-paginated `GET /api/v1/documents` (org docs via a new
  `document:read` / `document:manage` pair in the permission catalog — additive, the reconciler
  hands them to existing OWNERs; personal docs owner-scoped like `files`);
  `GET /{id}` → 302 presigned download (the files pattern); `DELETE /{id}`.
- **Delete semantics (decision #4 below, proposed):** DELETE removes the object immediately and
  soft-deletes the row — the metadata trail survives for audit/retention, the bytes do not; a
  restore recovers the record of the document, never its content. (The alternative — objects
  linger until the retention purge — needs a document-owned storage sweeper; deferred unless you
  want restorable content.)
- **Search tie-in (the first consumer of §2's `SearchIndex` port, proving that seam):** register
  the document title on create, remove on delete — documents become findable the moment search
  exists.
- **Deferred slices:** versioning, tags/labels, per-document ACLs.
- **Gate:** upload → list → 302 download → delete against real SeaweedFS; org A's documents never
  list for org B; a personal document is invisible to other users but reachable by
  `platform-support` (the files tiering); the registered title is searchable and un-searchable
  after delete.

## 4. `exchange` — the Data Exchange Platform

Implements [reusable-data-exchange-platform-guidelines.md](reusable-data-exchange-platform-guidelines.md)
verbatim: jobs not requests, records not files, domain logic stays in domain services. Where it
**relates** to `document`: every artifact a job touches — the uploaded source, the row-error
report, the export result — is registered through the `Documents` port, so tenants browse their
exchange history as documents; `exchange` still never knows what a "member" is.

- **Model (V24).** `exchange_job`: type (`IMPORT|EXPORT`), `handler varchar(60)` (which business
  port), `format` (`CSV|JSON` first), `status` — the guideline lifecycle `PENDING → VALIDATING →
  PROCESSING → COMPLETED | COMPLETED_WITH_ERRORS | FAILED | CANCELLED` — `source_key`/`result_key`
  (S3 keys via the existing `files` port), `total/processed/failed` counts, `next_offset bigint`
  (resume point), `error_report_key`, `locked_at`, attempts. Claim/fence/stale-reclaim exactly per
  §7 (`WebhookDeliveryQueue` is the reference); progress updates are fenced writes.
- **Pipeline (internal SPI).** `Reader → Parser → RecordValidator chain → ExchangeHandler`:
  - `FormatReader`/`FormatWriter` per format — **streaming only** (the CSV reader hands out
    records from an `InputStream`; nothing buffers the file). CSV first (hand-rolled RFC-4180
    or commons-csv — decision below), JSON Lines second. Excel deliberately deferred (POI is a
    heavyweight dependency; its own slice if wanted).
  - Validation layers per the guidelines: file (size/format/encoding) → structure (header/
    columns) → row (types/required) → business (the handler's own rules) — each producing
    row-addressed, actionable errors collected into an **error report CSV** written to S3.
- **The domain seam.** API port `ExchangeHandler { id(), template(), validate(record),
  apply(record) / query(cursor) }` — implemented by business modules, invoked by the platform.
  Reference implementation: **organization members import/export** driving the existing
  `MemberService.invite` (guideline #3: the same service REST uses — the escalation guard and
  last-owner rules apply for free). Idempotency: `apply` must be idempotent per record's natural
  key (invite already is), so a resumed/retried job is safe at-least-once.
- **REST.** `POST /api/v1/exchange/imports` (multipart, or a presigned-upload key + handler id)
  → 201 job; `POST /api/v1/exchange/exports` (handler + filter params) → 201 job;
  `GET /api/v1/exchange/jobs[/{id}]` cursor-paginated with progress; `POST …/{id}/cancel`;
  `GET …/{id}/report` and export results → 302 presigned (the `files` pattern). Org-scoped by the
  handler's declared permission (e.g. members import requires `member:invite`); job rows are
  tenant-filtered in the query.
- **Worker.** One poller per instance on virtual threads (the two delivery workers are the
  model): claim a job, stream records in configurable batches (default 500) committing progress
  per batch with `next_offset`, so a crashed instance's job is reclaimed and **resumes, not
  restarts**. Cancellation checked between batches. Timeouts on every remote touch (S3 reads
  stream through the existing client's timeouts).
- **Gate:** a 100k-row members CSV imports in bounded batches over real Postgres + SeaweedFS with
  the worker killed and reclaimed mid-run (resumes at `next_offset`, no duplicate members —
  proving idempotent resume); a validation-failure file yields `COMPLETED_WITH_ERRORS` + a
  row-addressed report on S3; export round-trips back to an importable file; `verify()` shows no
  new dependency from `exchange` into any module's internals.

## 5. `observability` — depth, not a module

The wiring exists (Actuator, OTLP, structured logs, virtual-thread metrics). This pass adds what
§9.2 called out as missing — **numbers someone can alert on** — plus the dashboards to see them.

- **Custom meters** (Micrometer, tagged): `smsone.deliveries.dead_lettered{queue,channel,reason}`
  (both delivery workers), `smsone.ratelimit.denied{tier}` + `RateLimit` headers on success
  responses (the deferred hardening item), `smsone.impersonation.sessions{action}`,
  `smsone.exchange.jobs{handler,outcome}` + records/sec, `smsone.cache{cache,result}` bound from
  the two-level cache, purge-job deletion counts.
- **Traces/logs:** `org_id` and `exchange.job_id` as span/MDC attributes where present (requestId
  already flows).
- **Dashboards:** Grafana JSON provisioned into `docker/` for otel-lgtm (delivery health,
  rate-limit pressure, cache hit ratio, exchange throughput, JVM/virtual threads) + example alert
  rules (dead-letter rate, breaker open, purge-job silence).
- **Gate:** an IT asserts the dead-letter and rate-limit counters actually increment through the
  real paths; `make run` renders the dashboards against live traffic (documented smoke, like the
  SeaweedFS one).

---

## Decisions to confirm before coding

1. **CSV dependency:** `commons-csv` (small, boring, battle-tested — proposed) vs hand-rolled
   RFC-4180.
2. **Search analyzer:** `simple` config (proposed — predictable, language-neutral, template-grade)
   vs `english` stemming; per-locale configs would join a later localization slice.
3. **Localized error `detail`s:** deferred slice B of localization (proposed) — in or out.
4. **Document delete semantics:** bytes go immediately, metadata soft-remains (proposed) — or
   restorable content via a storage sweeper.

## Carried backlog (former NEXT_TASKS items — parked here until told otherwise)

CI-green confirmation on GitHub (first run is executing on `560acd8` now) · Kubernetes migration
(Kustomize, plan §5.2) · event externalization when a broker lands · rate-limit hardening
(local fallback, egress decoupling — success headers move into the observability pass above) ·
notification claim-index `EXPLAIN` + dedicated worker DataSource · SRS §9.2 product gaps
(settings/flag DELETE routes, user-disable/erasure path, restore-over-HTTP decision, webhook
secret encryption-at-rest, CORS, real SMS provider, OpenAPI error schemas, negative-audience
test).
