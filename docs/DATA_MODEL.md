# Data Model

Every table in the database, its columns and its relationships. Generated from
`src/main/resources/db/migration`, which is the source of truth — and since ADR 0010 Phase 2 that is
two sequences, `platform/` and `tenant/`. Which one created a table is the same statement as its tier.

**62 tables**, grouped by owning module, alphabetical within each group. Seven of them were created in
both schemas and are counted once, here and in every total below. (`flyway_schema_history` is created
by Flyway itself, is not declared in any migration, and is not counted here.)

## Column conventions

These columns mean the same thing everywhere they appear and are not re-explained per table:

- `version` — optimistic-locking counter; a stale write is rejected.
- `created_at` / `updated_at` — when the row was inserted / last changed.
- `created_by` / `updated_by` — `person.id` of the human responsible; **NULL means a system job or a
  machine credential, not a missing value**. Soft ref to `person.id`, no FK, on every table.
- `deleted_at` — soft delete. NULL = live. A table without this column is never deleted, only
  superseded or trimmed by retention.
- **Unique among live rows** — a partial unique index over `deleted_at is null`, so a soft-deleted
  row releases its key for re-use.
- **Soft ref, no FK** — the value points at a row the database does not make it point at. Two rules
  produce these: no foreign key crosses a **module** boundary, and since V53 none crosses a **tenancy
  tier** boundary either (below). A foreign key is real only when both ends are in the same module
  *and* the same tier.

## Tenancy tier — and the schema it now names

Every table carries a **Tier**, from ADR 0010 §2. Through Phase 1 it recorded a decision and nothing
more: every tier still resolved to the single schema all the tables occupied. Phase 2 moved them, so a
tier is now also the answer to *where is this table, and how is it addressed*.

- **platform** — created in the `platform` schema; one copy for the whole installation. `platform` is
  on no tenant's `search_path`, so it is only ever reached by being **named**: entity mappings declare
  `schema = "platform"` and hand-written SQL writes `platform.<table>`. A tenant lifted onto its own
  database gets a projection or a snapshot of these, never the rows themselves.
- **tenant** — created in the tenant schema, which is `tenant_pool` while the tenant is pooled and
  `t_<32hex>` once it is promoted. Addressed **unqualified**, always, so the connection's `search_path`
  decides which tenant answers — that is the only form that survives promotion, since no schema name
  compiled into the binary can name a silo. A tenant that cannot answer a question from these tables
  alone is not extractable, which is the property the tier exists to protect.
- **platform + tenant** — the same DDL ran in **both** schemas: one table, two homes, seven of them
  (`audit_log`, `document`, `exchange_job`, `exchange_job_error`, `integration`, `integration_setting`,
  `maintenance_window`). Which copy a row belongs to is decided by `org_id` — non-NULL is the tenant's
  and travels with it, NULL is the platform's and stays. `exchange_job_error` and `integration_setting`
  have no `org_id` and follow their parent. Both forms of address are legal for them and mean different
  rows: bare reaches the tenant copy, `platform.<table>` the platform one. Each tier line below states
  its own rule, because NULL does not mean the same thing in all seven.

## The schemas

- `platform` — the 35 platform-tier tables, the platform copy of the seven split ones, and Flyway's own
  `flyway_schema_history`.
- `tenant_pool` — the 20 tenant-tier tables and the tenant copy of the seven. Every tenant lives here
  until it is promoted, so inside this schema `org_id` is the only thing separating them: **every
  tenant query keeps its `org_id` predicate**, and in a silo it is redundant but free and remains the
  detector that catches a misrouted write.
- `ext` — `pg_trgm`, and nothing else. It sits on every tenant's path so `word_similarity` and the `%`
  operator resolve, without putting a data-bearing schema there. It holds no table.
- `no_tenant` — real, empty, and permanently so. A connection borrowed with no tenant declared is
  pointed at it, so an unqualified tenant read fails with `relation "…" does not exist` rather than
  quietly answering out of someone else's rows.
- `public` — holds nothing and is on no path.

Two migration sequences, one counter: `db/migration/platform/` runs at boot with `platform` as its
default schema, and `db/migration/tenant/` holds the tenant tier's DDL, run against a tenant schema. A
table's tier is which directory created it; the seven split tables were created by both, from the same
DDL. V-numbers are one global sequence across the two directories and are deliberately non-contiguous
within either — `db/migration/platform/V1__baseline.sql` carries the rule and the reason.

Ownership decides the tier, not column presence: `ticket_message`, `org_group_member` and
`role_permission` have no `org_id` of their own and are still the tenant's, reached through a parent
that has one. A clause follows the tier only where ownership is not what the column list suggests —
and on every `platform + tenant` table, where it states the deciding rule.

**No foreign key may connect two tiers.** A tier boundary is now a schema boundary and later a database
boundary, and a foreign key cannot span either. `TenancyTierBoundaryTest` enforces both halves of this:
every table in the database has a tier recorded here, and no FK joins two tables of different tiers. A
table added in a later migration fails the build until it has a tier — which is the whole point, because
a boundary rots by accretion, not by decision.

**And no table may be addressed by the wrong tier.** `PlatformSchemaQualificationTest` reads the same
`**Tier:**` lines and fails the build when an entity mapping or a SQL literal names a platform-tier
table without `platform.`, or a tenant-tier one with any schema at all. Both mistakes fail silently at
runtime — the first resolves to nothing, the second reads the pool for a tenant that has been promoted
out of it — which is why they are a build gate and not a review note.

---

# access

### org_security_policy
One organization's access-tightening rules; every field only ever narrows what the platform default allows.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant this policy governs; unique among live rows |
| ip_allowlist | text | yes | Comma-joined CIDR ranges callers must originate from; empty means no IP restriction |
| require_trusted_device | boolean | no | When true, the caller's device must be marked trusted |
| session_max_age_seconds | bigint | yes | Reject tokens issued longer ago than this; NULL uses the platform default |
| require_mfa | boolean | no | When true, human sessions must present a multi-factor authentication claim |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the policy was created |
| created_by | uuid | yes | Who created it; NULL = system or machine |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it; NULL = system or machine |
| deleted_at | timestamptz | yes | Soft delete; NULL = in force |

Relationships: `org_id` → `organization.id` (soft ref, no FK); `created_by` / `updated_by` → `person.id` (soft ref, no FK).

### user_device
One device a person signs in from. Since V51 trust is not a property of the device: it is a grant one
organization makes over it, in `user_device_trust`.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| person_id | uuid | no | The person who registered the device |
| name | varchar(100) | no | Human-readable label for the device |
| kind | varchar(10) | no | BROWSER, MOBILE or CLI |
| fingerprint | varchar(100) | no | Opaque client-supplied device identifier; unique per person among live rows |
| push_token | varchar(300) | yes | Address for a future push-notification channel |
| last_seen_at | timestamptz | yes | Last request seen from this device |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the device was registered |
| created_by | uuid | yes | Who registered it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; a revoked device keeps its row as the trail |

Relationships: `person_id` → `person.id` (soft ref, no FK); `created_by` / `updated_by` → `person.id` (soft ref, no FK).

### user_device_trust
One organization's standing decision to trust one device. The absence of a row is the absence of trust, so revoking is a real delete and an org that never granted it never trusted it.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| device_id | uuid | no | The device trusted; half the primary key |
| org_id | uuid | no | The tenant granting the trust; half the primary key. A grant never reaches beyond the org that made it — before V51 it did, and that was a cross-tenant bypass |
| granted_at | timestamptz | no | When the grant was made |
| granted_by_person_id | uuid | yes | Who granted it; NULL = a system grant |
| person_id | uuid | no | Copy of `user_device.person_id`, so the enforcement lookup touches this table alone. Never updated — a grant whose device changed hands is deleted, not corrected |
| fingerprint | varchar(100) | no | Copy of `user_device.fingerprint` — the device identifier the request presents. Never updated, for the same reason |

Relationships: `device_id` → `user_device.id`, `org_id` → `organization.id`, `granted_by_person_id` → `person.id` — all soft refs, no FK. Primary key (`device_id`, `org_id`).

The copied columns replace a join, and the join was a security control: V53 cut the FK because `user_device` is platform-tier and this table is the tenant's, and the `d.deleted_at is null` the join carried is what stopped a revoked device's surviving grant from satisfying the policy. Nothing in this table can see that column any more, so **the existence of a row must itself mean the device is live** — maintained by an event-driven delete on revocation plus the `SoftDeletePurgeJob` reconciler behind it. A stale row here does not merely go stale: re-registering a revoked fingerprint mints a new device row, so the grant would vouch for a device the org has never seen.

---

# apikeys

### api_key
One machine credential: the public prefix, the hash of the secret, and the permissions it may exercise.

**Tier:** platform — `findByPrefix` resolves a key against a global uniqueness index before any tenant is known: the key *is* how the tenant is discovered, and a platform key has no org at all.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | yes | The tenant that owns this key; NULL = a platform key |
| owner_person_id | uuid | yes | The person this key acts as, for a personal access token; NULL = an org or platform key, which is nobody |
| name | varchar(100) | no | Operator-chosen label |
| prefix | varchar(20) | no | The public half, always displayable; unique among live rows |
| secret_hash | varchar(64) | no | SHA-256 hex of the secret half; the plaintext is never stored |
| permissions | text | yes | Comma-joined org permission codes this key may exercise |
| platform_tier | varchar(30) | yes | Privilege level for a platform key |
| expires_at | timestamptz | yes | When the key stops authenticating; NULL = no expiry |
| last_used_at | timestamptz | yes | Last successful authentication with this key |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the key was minted |
| created_by | uuid | yes | Who minted it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; revoking a key keeps the row |

Relationships: `org_id` → `organization.id` (soft ref, no FK); `owner_person_id`, `created_by`, `updated_by` → `person.id` (soft ref, no FK).

### api_key_prefix_reservation
The public key prefixes that may never be minted again. Written when a tenant is extracted (ADR 0010 §6 item 8), read by every mint.

**Tier:** platform — the reservation guards `uq_api_key_prefix_live`, which is global, and it is consulted on the same pre-tenant path `api_key` itself lives on.

| Column | Type | Null | Description |
|---|---|---|---|
| prefix | varchar(20) | no | The public half that is burned forever; primary key |
| org_id | uuid | yes | The organization whose key it was; NULL = it was a platform key. Soft ref, no FK — the reservation outlives the row it names |
| reserved_at | timestamptz | no | When the extraction burned it |
| reason | varchar(200) | no | Which extraction burned it, for the operator who meets a refused prefix years later |

Relationships: `org_id` → `organization.id` (soft ref, no FK). No `version`, no `deleted_at`, deliberately: a reservation is never edited and never released — releasing one is exactly the failure it exists to prevent.

Revoking a key is a **soft** delete and `uq_api_key_prefix_live` is partial on `deleted_at is null`, so revocation puts the prefix back into a 48-bit space. Re-minting a departed tenant's prefix for another organization makes a stale credential resolve against a different tenant's row on `findByPrefix` — the one lookup that runs before any tenant is known, and the reason `api_key` is platform-tier at all. The hash still has to match, so it is a cross-tenant lookup collision rather than a bypass; this table replaces a probability argument with a constraint. It is deliberately **not** in an extraction bundle: the lifted deployment mints into a prefix space of its own, where the platform's burned prefixes forbid nothing.

---

# audit

### audit_log
One recorded state change: what happened, who is accountable, and the before/after values. Append-only.

**Tier:** platform + tenant — routed on `org_id`: a non-NULL row is the tenant's compliance record and travels with it; a NULL row (impersonation lifecycle, platform key mint/revoke, flag changes) is the platform's and stays, because a support investigation cannot fan out.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | yes | The tenant the change happened in; NULL = a platform-level event |
| action | varchar(80) | no | What happened, e.g. `organization.member_added` |
| actor_person_id | uuid | yes | The accountable human; NULL = a system job or a machine key |
| target | varchar(320) | yes | The affected entity, read through `action` — a person id, an alias, a role id, a setting key |
| occurred_at | timestamptz | no | When the change itself happened |
| from_state | varchar(1000) | yes | The value before the change |
| to_state | varchar(1000) | yes | The value after the change |
| on_behalf_of_person_id | uuid | yes | The identity being worn during impersonation; NULL on an ordinary request |
| impersonation_id | uuid | yes | The impersonation session this change was made under |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the change was recorded (the audit timeline) |
| created_by | uuid | yes | Who wrote the entry |
| updated_at | timestamptz | yes | When the entry last changed |
| updated_by | uuid | yes | Who last changed it |

Relationships: `org_id` → `organization.id`, `actor_person_id` / `on_behalf_of_person_id` / `created_by` / `updated_by` → `person.id`, `impersonation_id` → `impersonation_session.id` — all soft refs, no FK.

---

# billing

### api_usage_daily
One organization's request count for one day, and whether it has been billed.

**Tier:** platform — the export job's cross-tenant `order by day` is load-bearing fairness: orgs a deadline-cut run never reached hold the oldest days and sort first tomorrow, which per-tenant tables cannot express.

| Column | Type | Null | Description |
|---|---|---|---|
| org_id | uuid | no | The tenant the requests are attributed to; half the primary key |
| day | date | no | The day counted; half the primary key |
| requests | bigint | no | Requests made by that tenant on that day |
| exported | boolean | no | Whether this day has been pushed to the billing system |

Relationships: `org_id` → `organization.id` (soft ref, no FK). Primary key (`org_id`, `day`).

### billing_account
The link between one organization and the account carrying its money in the external billing system.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant being billed; unique among live rows |
| kb_account_id | uuid | no | The account's identifier in the external billing system |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the link was made |
| created_by | uuid | yes | Who made it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; frees the org's slot |

Relationships: `org_id` → `organization.id` (soft ref, no FK); `created_by` / `updated_by` → `person.id` (soft ref, no FK).

---

# compliance

### consent_record
One consent decision a person made about one purpose, at one moment. Append-only — a withdrawal is a new row.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| person_id | uuid | no | The person whose decision this is |
| purpose | varchar(60) | no | What was consented to, e.g. marketing, analytics, product-updates |
| granted | boolean | no | True = granted, false = withdrawn |
| source | varchar(60) | yes | Where the decision came from (ui, import, api) |
| created_at | timestamptz | no | When the decision was recorded |

Relationships: `person_id` → `person.id` (soft ref, no FK).

### erasure_request
One request to erase a person's data, and its outcome.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| person_id | uuid | no | The person whose data is to be erased |
| requested_by_person_id | uuid | no | Who asked; equals `person_id` on a self-service request, differs on an admin-initiated one |
| status | varchar(20) | no | RECEIVED, EXECUTED or REFUSED |
| detail | varchar(300) | yes | Outcome note or refusal reason |
| created_at | timestamptz | no | When the request was filed |
| updated_at | timestamptz | yes | When it was last acted on |

Relationships: `person_id` and `requested_by_person_id` → `person.id` (soft ref, no FK).

### legal_hold
A standing instruction that one person's or one organization's data must not be hard-deleted. Released, never deleted.

**Tier:** platform — there must be exactly ONE set of holds. A per-tenant copy the purge job cannot see is a hold that silently stops holding, which is the failure this table exists to prevent.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| scope | varchar(10) | no | SUBJECT or ORG — which of the two id columns below is set |
| person_id | uuid | yes | The held person; set when scope is SUBJECT |
| org_id | uuid | yes | The held tenant; set when scope is ORG |
| reason | varchar(300) | no | Why the hold was placed |
| placed_by_person_id | uuid | no | The human accountable for the hold; a machine credential cannot place one |
| placed_at | timestamptz | no | When the hold took effect |
| released_at | timestamptz | yes | When it was lifted; NULL = still in force |
| released_by_person_id | uuid | yes | Who lifted it |

Relationships: `person_id`, `placed_by_person_id`, `released_by_person_id` → `person.id`; `org_id` → `organization.id` — all soft refs, no FK.

---

# document

### document
The business record of one stored file — who owns it, what it is called, and where its bytes live.

**Tier:** platform + tenant — routed on `org_id`: non-NULL is the org's document and travels with it; NULL means a *personal* document, which belongs to a human and must not.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | yes | The owning tenant; NULL means a personal document, not an unknown tenant |
| owner_person_id | uuid | no | The person who owns the document |
| storage_key | varchar(300) | no | Key into the files namespace holding the bytes; unique among live rows — but that index now exists once per schema, so the KEY's own shape is what separates two tenants: an org document sits under `doc/o/<orgId>/` or `exch/o/<orgId>/` and a personal one under `doc/u/<personId>/`, enforced by `NewDocument` against `shared.document.OrgObjectPrefixes` and copied by prefix at extraction (ADR 0010 §6 item 7) |
| name | varchar(255) | no | Filename shown to users |
| content_type | varchar(100) | no | MIME type of the stored object |
| size_bytes | bigint | no | Size of the stored object |
| source | varchar(20) | no | UPLOAD or EXCHANGE — how the document arrived |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the document was recorded |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; the row remains as the metadata trail after the object itself is gone |

Relationships: `org_id` → `organization.id`; `owner_person_id`, `created_by`, `updated_by` → `person.id`; `storage_key` → the files namespace — all soft refs, no FK.

---

# exchange

### exchange_job
One import or export run: what was asked for, how far it got, and where its files are.

**Tier:** platform + tenant — routed on `org_id`, except V25 set the column NOT NULL ("a null here today is a bug, not a feature"), so every row is tenant-tier until a real platform-job design earns the relaxation back.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant that submitted the job |
| requester_person_id | uuid | no | The person the job runs as; their permissions are re-checked per record at processing time |
| job_type | varchar(10) | no | IMPORT or EXPORT |
| handler | varchar(60) | no | Identifier of the handler that knows this data shape |
| format | varchar(10) | no | CSV or JSONL |
| status | varchar(30) | no | PENDING, VALIDATING, PROCESSING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED or CANCELLED |
| source_key | varchar(300) | yes | Files-namespace key of the uploaded import source |
| result_key | varchar(300) | yes | Files-namespace key of the export output |
| error_report_key | varchar(300) | yes | Files-namespace key of the row-addressed error report |
| handler_version | int | no | Which template shape the job was submitted against |
| processed | bigint | no | Rows handled so far |
| failed | bigint | no | Rows that failed so far |
| next_offset | bigint | no | Row offset a reclaimed job resumes from, so a crash does not restart the file |
| attempts | int | no | Claim generation; every fenced status update checks it |
| cancel_requested | boolean | no | Asks a running job to stop at the next batch boundary |
| locked_at | timestamptz | yes | When the current worker claimed the job |
| last_error | text | yes | Why the last attempt failed |
| created_at | timestamptz | no | When the job was submitted |
| updated_at | timestamptz | yes | When its state last changed |

Relationships: `org_id` → `organization.id`; `requester_person_id` → `person.id` — soft refs, no FK. Referenced by `exchange_job_error.job_id` (real FK, cascade).

### exchange_job_error
One source row that failed, and why. Durable so a crash loses no error.

**Tier:** platform + tenant — no `org_id` of its own; it follows its parent job through the FK, so it lands wherever that job lands.

| Column | Type | Null | Description |
|---|---|---|---|
| job_id | uuid | no | The job that produced the error; half the primary key |
| row_num | bigint | no | Which row of the source failed; half the primary key, making replayed batches idempotent |
| error | varchar(500) | no | Why that row failed |

Relationships: `job_id` → `exchange_job.id` (real FK, cascade delete). Primary key (`job_id`, `row_num`).

### exchange_schedule
A recurring export: a handler, a cron expression, and the person it runs as.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant that owns the schedule |
| requester_person_id | uuid | no | The person each fired job runs as; permissions are re-checked on every fire |
| handler | varchar(60) | no | Identifier of the handler to run |
| format | varchar(10) | no | CSV or JSONL |
| cron | varchar(120) | no | Six-field cron expression, validated on create |
| enabled | boolean | no | Whether the poller may fire it |
| next_run_at | timestamptz | no | When it is next due |
| last_job_id | uuid | yes | The job most recently fired from this schedule |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the schedule was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete |

Relationships: `org_id` → `organization.id`; `requester_person_id`, `created_by`, `updated_by` → `person.id`; `last_job_id` → `exchange_job.id` — all soft refs, no FK (a schedule outlives the jobs retention trims).

---

# geo

### geo_capture_policy
The per-organization, per-record-type switch deciding whether a location may, must, or must not be captured.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant this policy applies to; unique with `subject_type` |
| subject_type | varchar(64) | no | The record type governed; unique with `org_id` |
| mode | varchar(16) | no | Whether capture is off, optional or required |
| min_accuracy_m | numeric(10,2) | yes | Reject fixes coarser than this many metres |
| allowed_sources | varchar(64) | yes | Comma-joined capture sources accepted |
| max_fix_age_seconds | integer | yes | Reject fixes older than this |
| retention_days | integer | yes | How long stamps of this type are kept |
| coarsen_after_days | integer | yes | Age after which stored precision is reduced |
| created_at | timestamptz | no | When the policy was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| version | bigint | no | Optimistic-locking counter |

Relationships: `org_id` → `organization.id` (soft ref, no FK); `created_by` / `updated_by` → `person.id` (soft ref, no FK). Unique constraint (`org_id`, `subject_type`) — total, not partial.

### geo_stamp
A position attached to some other record at a moment in time.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant that owns the stamp; every read is scoped by it |
| subject_type | varchar(64) | no | Discriminator naming what kind of record `subject_id` points at |
| subject_id | varchar(64) | no | Polymorphic key of the stamped record; holds a person key only on person-shaped rows |
| latitude | numeric(9,6) | no | WGS-84 latitude |
| longitude | numeric(9,6) | no | WGS-84 longitude |
| accuracy_m | numeric(10,2) | yes | Reported horizontal accuracy radius in metres |
| altitude_m | numeric(10,2) | yes | Reported altitude in metres |
| source | varchar(16) | no | How the fix was obtained |
| captured_by_person_id | uuid | yes | Who captured it; NULL = an unauthenticated or a machine capture |
| captured_at | timestamptz | no | When the fix was taken |
| consent_ref | varchar(64) | yes | The consent record this capture was taken under |
| country_code | varchar(2) | yes | Reverse-geocoded country; NULL until geocoding runs |
| admin1 | varchar(120) | yes | Reverse-geocoded first-level administrative area |
| locality | varchar(160) | yes | Reverse-geocoded city or town |
| formatted_address | text | yes | Reverse-geocoded full address |
| place_id | varchar(128) | yes | The geocoder's own identifier for the place |
| geocoder_provider | varchar(24) | yes | Which geocoder produced the place columns |
| created_at | timestamptz | no | When the stamp was recorded |
| created_by | uuid | yes | Who recorded it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| version | bigint | no | Optimistic-locking counter |
| deleted_at | timestamptz | yes | Soft delete |

Relationships: `org_id` → `organization.id`; `captured_by_person_id`, `created_by`, `updated_by` → `person.id`; `consent_ref` → `consent_record.id`; (`subject_type`, `subject_id`) → any record type — all soft refs, no FK.

---

# identity

### external_identity
One login a person holds at one external provider — the only place in the schema storing an identifier minted elsewhere.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| person_id | uuid | no | The person this login belongs to |
| provider | varchar(20) | no | KEYCLOAK, GOOGLE, MICROSOFT, APPLE, PASSKEY, SAML, LDAP, API_KEY or INTERNAL |
| issuer | varchar(300) | no | The realm or tenant the subject is unique within; never NULL, so the uniqueness below cannot be evaded |
| external_subject | varchar(255) | no | The person's identifier at that provider |
| external_username | varchar(320) | yes | What they are called over there; display only |
| linked_at | timestamptz | no | When the link was established |
| last_authenticated_at | timestamptz | yes | Last sign-in through this provider |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the row was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; unlinking keeps the row |

Relationships: `person_id` → `person.id` (**real FK**, cascade delete — same module); `created_by` / `updated_by` → `person.id` (soft ref, no FK). Unique among live rows: (`provider`, `issuer`, `external_subject`) — the key every authenticated request resolves through — and (`person_id`, `provider`, `issuer`).

### impersonation_session
The standing record of one operator acting inside someone else's account. Ends, never deletes.

**Tier:** platform — an opaque session id is resolved at `@Order(-2)`, before any tenant is known; the org it names is the scope, not the owner.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| actor_person_id | uuid | no | The operator answerable for the session |
| target_person_id | uuid | no | The identity being worn |
| target_display | varchar(320) | yes | The target's label copied at open time and never refreshed, so the trail stays readable after the account is gone |
| org_id | uuid | yes | The tenant the session is scoped to; NULL = unscoped |
| reason | varchar(500) | no | The stated justification |
| mode | varchar(20) | no | READ_ONLY or WRITE |
| started_at | timestamptz | no | When the session opened |
| expires_at | timestamptz | no | Server-clamped deadline, evaluated on read |
| ended_at | timestamptz | yes | NULL while live; set on end or supersede and never cleared |
| ended_by_person_id | uuid | yes | Who ended it |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the row was written |
| created_by | uuid | yes | Who wrote it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |

Relationships: `actor_person_id`, `target_person_id`, `ended_by_person_id`, `created_by`, `updated_by` → `person.id` (soft ref, no FK — deliberately, so the record outlives the accounts it names, even though `person` is the same module); `org_id` → `organization.id` (soft ref, no FK).

### person
The canonical identity of a human on this platform — whether they are allowed in, and what they are called.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key; the identifier every other module means when it says "a person" |
| status | varchar(20) | no | INVITED, ACTIVE or DISABLED |
| invited_at | timestamptz | no | When the account was created for them |
| activated_at | timestamptz | yes | First real use of the account; NULL until the human turns up |
| disabled_at | timestamptz | yes | When access stopped |
| formatted_name | varchar(200) | yes | The display name, supplied whole — never built from the parts below, whose order is cultural |
| given_name | varchar(100) | yes | Given name, for sorting, matching and form pre-fill |
| family_name | varchar(100) | yes | Family name; nullable because mononyms and patronymics are ordinary |
| middle_name | varchar(100) | yes | Middle name |
| honorific_prefix | varchar(50) | yes | Dr., Prof., Rev. |
| honorific_suffix | varchar(50) | yes | Jr., PhD, MBE |
| preferred_name | varchar(100) | yes | What they asked to be called — part of who they are, unlike the profile's display name |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the row was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete |

Relationships: referenced by `external_identity.person_id` and `person_contact.person_id` (**real FKs**, cascade delete). Every other reference to a person anywhere in the schema is a soft ref with no FK.

### person_contact
One address the platform can reach a person at, and whether that address has been proven.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| person_id | uuid | no | The person reachable at this address |
| kind | varchar(10) | no | EMAIL, PHONE or OTHER |
| contact_value | varchar(320) | no | The address itself |
| label | varchar(50) | yes | User-chosen label such as work or home |
| is_primary | boolean | no | The one address of this kind to use by default; unique per person per kind among live rows |
| verified_at | timestamptz | yes | When the address was proven; NULL = claimed but never proven. A proven address is globally unique per kind, case-insensitively, among live rows |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the contact was added |
| created_by | uuid | yes | Who added it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete |

Relationships: `person_id` → `person.id` (**real FK**, cascade delete — same module); `created_by` / `updated_by` → `person.id` (soft ref, no FK).

---

# integration

### integration
Which external provider serves a given capability for a given organization.

**Tier:** platform + tenant — routed on `org_id`: non-NULL is the org's own provider choice; NULL is the platform default used when an org has no override.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | yes | The tenant this integration serves; NULL = the platform default used when an org has no override |
| kind | varchar(20) | no | SMS_PROVIDER, EMAIL_PROVIDER or PAYMENT_GATEWAY |
| provider | varchar(40) | no | Provider code, e.g. twilio, smtp, stripe |
| enabled | boolean | no | Whether resolution may select this row |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When it was configured |
| created_by | uuid | yes | Who configured it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete |

Relationships: `org_id` → `organization.id` (soft ref, no FK); `created_by` / `updated_by` → `person.id` (soft ref, no FK). Referenced by `integration_setting.integration_id` (real FK, cascade). Unique among live rows: (`org_id`, `kind`) for org rows, (`kind`) for platform-default rows.

### integration_setting
One configuration value for one integration.

**Tier:** platform + tenant — no `org_id` of its own; it follows its parent integration through the FK.

| Column | Type | Null | Description |
|---|---|---|---|
| integration_id | uuid | no | The integration configured; half the primary key |
| setting_key | varchar(60) | no | Name of the setting; half the primary key |
| setting_value | varchar(600) | no | The value — plaintext, or an encrypted envelope when the setting is secret |
| is_secret | boolean | no | Whether the value is encrypted at rest and masked on read |

Relationships: `integration_id` → `integration.id` (**real FK**, cascade delete). Primary key (`integration_id`, `setting_key`).

---

# localization

### translation
One translated message for one locale.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| locale | varchar(35) | no | Lowercased BCP-47 tag, so lookups never depend on tag casing |
| msg_key | varchar(200) | no | The message key; unique with `locale` among live rows |
| msg_value | text | no | The translated text |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the translation was added |
| created_by | uuid | yes | Who added it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; frees the (locale, key) slot |

Relationships: `created_by` / `updated_by` → `person.id` (soft ref, no FK).

---

# maintenance

### maintenance_window
A scheduled period during which the platform announces, or enforces, reduced availability.

**Tier:** platform + tenant — routed on `org_id`: NULL is a platform-wide window every request reads; non-NULL is the tenant's own. Both are read on the same request, so the platform half is the one to cache.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | yes | The tenant covered; NULL = the whole platform |
| starts_at | timestamptz | no | When the window opens |
| ends_at | timestamptz | no | When it closes |
| mode | varchar(10) | no | ANNOUNCE (clients show a notice) or RESTRICT (org-scoped writes are refused, reads pass) |
| message | varchar(300) | no | The notice shown to users |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the window was scheduled |
| created_by | uuid | yes | Who scheduled it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; cancelling keeps the record that it existed |

Relationships: `org_id` → `organization.id` (soft ref, no FK); `created_by` / `updated_by` → `person.id` (soft ref, no FK).

---

# notification

### in_app_notification
One message addressed to a person inside this platform's own UI.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| person_id | uuid | no | The person the notification is for |
| subject | varchar(255) | no | The notification's subject line |
| body | text | yes | The notification's text |
| read_at | timestamptz | yes | When the person read it; NULL = unread |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When it was raised |
| created_by | uuid | yes | Who raised it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |

Relationships: `person_id`, `created_by`, `updated_by` → `person.id` (soft ref, no FK). Deliberately carries no `org_id`: a notification belongs to the person, not to the tenant they were acting in.

### notification_delivery
One outbound message on the delivery queue: where it goes, and how far the attempt has got.

**Tier:** platform — pure transport claimed by a cluster-wide sweep on a 1 s poll; per-tenant queues make an empty discovery pass cost more than the poll interval.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| channel | varchar(20) | no | Which transport carries it, and the discriminator `recipient` is read through |
| recipient | text | no | The address to deliver to — an email address, a phone number, a webhook URL, or a person id only when the channel is in-app |
| subject | varchar(255) | no | The message's subject line |
| body | text | yes | The message's text |
| status | varchar(20) | no | PENDING, PROCESSING, SENT or FAILED |
| attempts | int | no | Delivery attempts made so far |
| max_attempts | int | no | Attempts allowed before the message is dead-lettered |
| next_attempt_at | timestamptz | no | When a worker may next claim it |
| locked_at | timestamptz | yes | When the current worker claimed it |
| last_error | text | yes | Why the last attempt failed |
| throttled_since | timestamptz | yes | When it first became blocked by the channel's rate limit; cleared whenever it is actually attempted |
| org_id | uuid | yes | The recipient's tenant, used to resolve that tenant's provider at send time; NULL = a platform notification |
| created_at | timestamptz | no | When it was enqueued |

Relationships: `org_id` → `organization.id` (soft ref, no FK); `recipient` → `person.id` only on in-app rows (soft ref, no FK).

---

# organization

### external_organization
One identifier an external system knows an organization by.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| organization_id | uuid | no | The tenant this identifier refers to |
| provider | varchar(20) | no | Same vocabulary as `external_identity.provider` |
| issuer | varchar(300) | no | The realm or tenant the external id is unique within; never NULL |
| external_org_id | varchar(255) | no | The tenant's identifier over there |
| external_alias | varchar(120) | yes | The tenant's slug over there; identity-provider org claims are keyed by it |
| linked_at | timestamptz | no | When the link was established |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the row was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete |

Relationships: `organization_id` → `organization.id` (**real FK**, cascade delete — same module); `created_by` / `updated_by` → `person.id` (soft ref, no FK). Unique among live rows: (`provider`, `issuer`, `external_org_id`); (`provider`, `issuer`, `external_alias`) where the alias is set; (`organization_id`, `provider`, `issuer`).

### membership
One person's place in one organization, under one role.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant they belong to |
| person_id | uuid | no | The person who belongs |
| role_id | uuid | no | The role granted to them directly, before any group role is unioned in |
| status | varchar(20) | no | ACTIVE or SUSPENDED |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When they joined |
| created_by | uuid | yes | Who added them |
| updated_at | timestamptz | yes | When the membership last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; a person may hold one live plus many removed rows per org, which is the joining history |

Relationships: `role_id` → `org_role.id` (**real FK** — same module, same tier); `org_id` → `organization.id` (soft ref, no FK — V53 cut it, because `organization` is platform-tier and this row is the tenant's); `person_id`, `created_by`, `updated_by` → `person.id` (soft ref, no FK — identity is another module). Unique among live rows: (`org_id`, `person_id`).

### org_group
A named group inside an organization that confers its role on every member, in addition to each member's own role.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant the group belongs to |
| name | varchar(100) | no | The group's name; unique per org among live rows |
| role_id | uuid | no | The role this group confers on its members |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the group was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete |

Relationships: `role_id` → `org_role.id` (**real FK** — same module, same tier); `org_id` → `organization.id` (soft ref, no FK — cut by V53, same reason as `membership`); `created_by` / `updated_by` → `person.id` (soft ref, no FK). Referenced by `org_group_member.group_id` (real FK, cascade).

### org_group_member
One person's place in one group.

**Tier:** tenant — no `org_id` of its own; it follows its group.

| Column | Type | Null | Description |
|---|---|---|---|
| group_id | uuid | no | The group; half the primary key |
| person_id | uuid | no | The person in it; half the primary key |

Relationships: `group_id` → `org_group.id` (**real FK**, cascade delete); `person_id` → `person.id` (soft ref, no FK). Primary key (`group_id`, `person_id`).

### org_membership_index
Which organizations a person holds a live seat in — the platform-side answer to a question no tenant schema can answer, because the answer spans tenants and the caller has not chosen one yet. It **routes; the tenant schema authorizes**, and the row is three routing keys precisely so there is nothing here to authorize with.

**Tier:** platform — `membership` is the tenant's, so the person-first read (`GET /api/v1/me/organizations`, and the operator's person view) would otherwise be a query per tenant schema on a route a multi-org human hits at every sign-in.

| Column | Type | Null | Description |
|---|---|---|---|
| person_id | uuid | no | The person; half the primary key |
| org_id | uuid | no | An organization they hold a live seat in; half the primary key |
| status | varchar(20) | no | Mirrors `membership.status` — ACTIVE or SUSPENDED |

Relationships: `person_id` → `person.id` and `org_id` → `organization.id` (soft ref, no FK); `membership` is the row this mirrors and there is **no FK to it and cannot be** — the two are in different tiers, and after a database split not even in the same database. Primary key (`person_id`, `org_id`); indexed by `org_id` for the org-first sweep. No `version`, `created_at` or `deleted_at`: it carries no history, is written inside the same transaction as the `membership` row it mirrors, and is deleted rather than tombstoned when the seat goes. `OrgMembershipIndexReconciler` is what replaces the missing constraint, for the paths that write `membership` in raw SQL and cannot know this exists.

### org_role
A named bundle of permissions inside one organization.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant the role belongs to |
| code | varchar(64) | no | Stable code the platform and API refer to, e.g. OWNER; unique per org among live rows |
| name | varchar(120) | no | Display name the org may change |
| system_role | boolean | no | True = seeded and not editable by the org; only OWNER is one |
| description | text | yes | What the role is for |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the role was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; frees the code for re-use in that org |

Relationships: `org_id` → `organization.id` (soft ref, no FK — cut by V53, same reason as `membership`); `created_by` / `updated_by` → `person.id` (soft ref, no FK). Referenced by `role_permission.role_id` (real FK, cascade), `membership.role_id` and `org_group.role_id` (real FKs) — all three tenant-tier, so they travel with the role.

### organization
A tenant. Its `id` is the tenant key every other module stores.

**Tier:** platform — the routing registry: the token's org claim resolves here *before* the tenant is known, so it is deliberately not mirrored into tenant schemas where a copy could drift from the authority.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key; the only internal tenant key |
| alias | varchar(120) | no | Local slug used in URLs and claims; unique among live rows |
| name | varchar(200) | no | Display name |
| status | varchar(20) | no | ACTIVE or SUSPENDED |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the tenant was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; the alias stays occupied until the row is purged |

Relationships: referenced by `external_organization.organization_id` (**real FK**, cascade — the only one left, and the only referrer that is platform-tier too). Every other `org_id` in the schema is a soft ref with no FK: V53 cut the last three real ones (`membership`, `org_role`, `org_group`), because a tenant-tier row cannot hold a foreign key into a platform-tier table it may one day not share a database with.

### role_permission
One permission granted by one role.

**Tier:** tenant — no `org_id` of its own, but it cascades from `org_role`, every row of which has one. It is a tenant's own grants, not platform reference data.

| Column | Type | Null | Description |
|---|---|---|---|
| role_id | uuid | no | The role granting it; half the primary key |
| permission | varchar(64) | no | The permission code granted; half the primary key |

Relationships: `role_id` → `org_role.id` (**real FK**, cascade delete). Primary key (`role_id`, `permission`).

---

# payments

### payment
One payment collection attempted through an external gateway.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant the payment is for |
| provider | varchar(32) | no | Which gateway is collecting, e.g. pesapal, yo-uganda |
| mode | varchar(16) | no | sandbox or live, stamped at initiation |
| merchant_reference | varchar(50) | no | Our reference sent to the gateway; globally unique |
| gateway_reference | varchar(100) | yes | The gateway's own identifier for the transaction |
| amount | numeric(19,2) | no | Gross amount charged, VAT-inclusive |
| currency | varchar(3) | no | ISO 4217 currency code |
| description | varchar(100) | no | What the payer is being charged for |
| phone_number | varchar(20) | yes | The payer's phone, captured per request; the payer need not be a person in this system |
| email | varchar(255) | yes | The payer's email, on the same terms |
| status | varchar(24) | no | PENDING, COMPLETED, FAILED, REVERSED, INVALID or INDETERMINATE |
| status_detail | varchar(255) | yes | The gateway's explanation of the status |
| confirmation_code | varchar(64) | yes | The gateway's receipt code |
| redirect_url | varchar(1024) | yes | Where the payer is sent to authorize |
| vat_amount | numeric(19,2) | yes | VAT component of `amount`; NULL when zero-rated |
| net_amount | numeric(19,2) | yes | `amount` minus VAT; NULL when zero-rated |
| created_at | timestamptz | no | When the payment was initiated |
| updated_at | timestamptz | no | When its status last converged |
| version | bigint | no | Optimistic-locking counter |

Relationships: `org_id` → `organization.id` (soft ref, no FK). Carries no person column: who initiated a payment is recorded only in the audit trail.

---

# profile

### person_preference
One small key/value setting belonging to one person.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| person_id | uuid | no | The person the preference belongs to; half the primary key |
| pref_key | varchar(100) | no | Name of the preference; half the primary key |
| pref_value | varchar(500) | no | The stored value |
| updated_at | timestamptz | no | When the preference was last set |

Relationships: `person_id` → `person.id` (soft ref, no FK — profile is a separable service). Primary key (`person_id`, `pref_key`).

### person_profile
A person's presentation settings: what they look like and are shown as in the UI.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| person_id | uuid | no | The person this profile describes; one live profile per person |
| display_name | varchar(150) | yes | The name to show in the UI — a preference, unlike `person.preferred_name` |
| avatar_key | varchar(300) | yes | Files-namespace key of the avatar image |
| timezone | varchar(50) | yes | IANA time-zone id used to render times |
| locale | varchar(20) | yes | Preferred BCP-47 locale |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the profile was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete |

Relationships: `person_id`, `created_by`, `updated_by` → `person.id` (soft ref, no FK — profile is a separable service, so no foreign key is taken); `avatar_key` → the files namespace (soft ref, no FK).

---

# scheduler

### org_retention_override
One organization's own retention period for one class of log, replacing the platform default.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant the override applies to; unique with `scope` |
| scope | varchar(40) | no | Which log it governs: WEBHOOK_DELIVERY or EXCHANGE_JOB; unique with `org_id` |
| retention_days | int | no | How many days that tenant's rows are kept |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the override was set |
| created_by | uuid | yes | Who set it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |

Relationships: `org_id` → `organization.id` (soft ref, no FK); `created_by` / `updated_by` → `person.id` (soft ref, no FK). Unique constraint (`org_id`, `scope`) — total, not partial.

---

# search

### search_document
One searchable projection of a record owned by some other module.

**Tier:** platform — derived data: an extraction rebuilds the index rather than copying it. Person rows are indexed with no org at all, and the uniqueness key has no org column.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | yes | The tenant the indexed record belongs to; NULL = platform-wide, reachable by admin search only |
| entity_type | varchar(40) | no | Discriminator naming what `entity_id` points at; unique with `entity_id` |
| entity_id | varchar(64) | no | Polymorphic key of the indexed record — a person id, an org id, a document id — never converted to a typed id |
| title | varchar(300) | no | Copy of the record's headline text |
| body | text | no | Copy of the record's searchable text |
| tsv | tsvector | yes | Full-text vector generated always from `title` and `body`; producers cannot write it directly |
| updated_at | timestamptz | no | When the projection was last refreshed |

Relationships: `org_id` → `organization.id` (soft ref, no FK); (`entity_type`, `entity_id`) → any indexed record (soft ref, no FK). Unique constraint (`entity_type`, `entity_id`) — total; the table is rebuildable, so rows are removed rather than soft-deleted.

---

# settings

### feature_flag
One named switch, plus its percentage rollout.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| flag_key | varchar(150) | no | The key code checks; unique among live rows |
| enabled | boolean | no | The global on/off |
| description | text | yes | What the flag controls |
| percentage | int | yes | Rollout percentage, bucketed deterministically by `organization.id`; only meaningful while enabled |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the flag was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; frees the key |

Relationships: `created_by` / `updated_by` → `person.id` (soft ref, no FK). Referenced by `feature_flag_org_override.flag_key` (soft ref, no FK — the parent's uniqueness is a partial index, which a foreign key cannot target).

### feature_flag_org_override
A hard per-organization answer for one flag, beating both the global switch and the percentage rollout.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| flag_key | varchar(150) | no | The flag being overridden |
| org_id | uuid | no | The tenant the override applies to |
| enabled | boolean | no | The answer this tenant gets |
| created_at | timestamptz | no | When the override was set |

Relationships: `flag_key` → `feature_flag.flag_key` and `org_id` → `organization.id` — both soft refs, no FK. Unique constraint (`flag_key`, `org_id`) — total, not partial; clearing an override is a real delete.

### setting
One system-wide configuration value.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| setting_key | varchar(150) | no | The key code reads; unique among live rows |
| setting_value | text | no | The configured value |
| description | text | yes | What the setting controls |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the setting was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; frees the key |

Relationships: `created_by` / `updated_by` → `person.id` (soft ref, no FK).

---

# shared (platform infrastructure)

### event_inbox
The mark that one listener has already handled one message, so a redelivery is skipped.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| listener_id | varchar(200) | no | The listener that handled it; half the primary key |
| message_id | varchar(200) | no | The message handled; half the primary key |
| processed_at | timestamptz | no | When it was handled |

Relationships: none. Primary key (`listener_id`, `message_id`).

### event_publication
One domain event queued for one listener, and whether that listener has completed it.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| listener_id | text | no | The listener the event is destined for |
| event_type | text | no | The event's class name |
| serialized_event | text | no | The event payload |
| publication_date | timestamptz | no | When the event was published |
| completion_date | timestamptz | yes | When the listener finished; NULL = still incomplete |
| status | text | yes | Publication state |
| completion_attempts | int | yes | Delivery attempts made |
| last_resubmission_date | timestamptz | yes | When it was last resubmitted |

Relationships: none. Owned by the Spring Modulith event registry.

### idempotency_key
One claimed HTTP idempotency key and the response it replays, scoped to the caller that used it.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| principal | varchar(200) | no | The caller the key is scoped to, so one caller's key never replays to another; half the primary key. Holds `person.id`, or a sentinel for unauthenticated calls — the one identity-bearing column deliberately not typed as a person id |
| idem_key | varchar(128) | no | The key the client supplied; half the primary key |
| request_hash | varchar(64) | no | Hash of the request body; a mismatch is a conflict, not a replay |
| response_status | int | yes | The stored response status; NULL means the original call is still in progress |
| response_body | text | yes | The stored response body |
| content_type | varchar(100) | yes | Content type of the stored response |
| created_at | timestamptz | no | When the key was claimed; rows age out on a retention window |

Relationships: `principal` may hold a `person.id` (soft ref, no FK). Primary key (`principal`, `idem_key`).

### queue_signal
Which tenants have unfinished work in which durable queue, and when each becomes claimable. One row per (queue, tenant) — **never one per message**: enqueue upserts once per BATCH, so a fan-out of forty thousand deliveries writes one row here. It is an index over the three queues, not a second copy of them.

**Tier:** platform — it is read BEFORE a tenant is chosen, which is the whole point: a durable queue's claim is a search for work that belongs to no tenant yet, so the table that answers "which tenant" cannot itself live in a tenant's schema.

| Column | Type | Null | Description |
|---|---|---|---|
| queue | text | no | Which queue — `webhook`, `notification` or `exchange`; half the primary key |
| org_id | uuid | no | The tenant with work, or the nil uuid `00000000-0000-0000-0000-000000000000` for rows that belong to no organization (a platform-scoped notification, a platform-scoped exchange handler); half the primary key |
| due_at | timestamptz | no | When this tenant becomes claimable — its earliest remaining row's time while nobody holds it, and `now() + stale-lock` while somebody does, so other workers skip it and a worker that dies mid-batch simply lets the stamp expire. Always minted by the database's clock, which is also the clock that reads it |
| lease | uuid | yes | Who holds the tenant right now, and null the rest of the time. A release only lands where this is still the token its claim returned; `raise` clears it, so an enqueue that arrives mid-batch cannot be buried by a release computed before it existed. A uuid rather than a re-read of `due_at` because identity must not be a timestamp comparison — see V56 |

Relationships: none, and `org_id` is a soft ref with no FK for two reasons at once — `organization` is platform-tier so a key would be legal, but the nil uuid is not an organization at all, and a signal must be raiseable for a queue row whose org was hard-deleted. Primary key (`queue`, `org_id`); indexed by (`queue`, `due_at`), which is exactly the claim's candidate `where queue = ? and due_at <= now() order by due_at limit 1 for update skip locked` — a MATERIALIZED CTE, so the candidate is evaluated once and one claim can lease only one tenant whatever plan the database picks. No `version`, `created_at` or `deleted_at`: the row is a hint about where to look next, never a record of anything, and it is DELETED the moment its queue has nothing left for that tenant. ADR 0010 §2.1; `shared.queue.QueueSignals` owns every statement against it.

### shedlock
The lock one scheduled task holds, so only one instance runs it.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| name | varchar(64) | no | Primary key; the scheduled task's lock name |
| lock_until | timestamp | no | When the lock lapses. Deliberately without time zone — the library compares database-clock UTC values |
| locked_at | timestamp | no | When the lock was taken; without time zone for the same reason |
| locked_by | varchar(255) | no | Which instance holds it |

Relationships: none. Owned by the ShedLock library.

### tenant_freeze
The tenants whose rows are being moved between schemas right now, and until when. One row per organization for the length of one promotion or demotion; empty the rest of the time.

**Tier:** platform — it is read to decide whether a tenant may be written at all, by workers that have not yet pinned a tenant axis, and by the promoter that is in the middle of emptying that tenant's schema. A freeze living in the schema being copied would be copied with it.

| Column | Type | Null | Description |
|---|---|---|---|
| org_id | uuid | no | `organization.id`, and the primary key — a tenant is frozen or it is not. Soft ref, no FK: a key here would cross a module boundary, and a freeze has to stay removable after its organization has been hard-deleted |
| reason | text | no | Why, in a sentence a human reads at 03:00 — "moving organization X from tenant_pool to t_…". Never parsed |
| frozen_at | timestamptz | no | When the freeze was taken, on the database's clock, because every comparison against it is made in SQL |
| expires_at | timestamptz | no | **When the freeze lapses on its own.** The most important column here: a reader treats an expired row as no freeze at all, so a promoter that is killed mid-run pauses a tenant's background work for a bounded window instead of forever. Set from `app.tenancy.promotion.freeze` |
| holder | text | no | Which process took it, so "is that promoter still alive" is answerable. Not a lease token — this table arbitrates nothing; two concurrent promotions are made impossible by `tenant_placement`'s conditional writes |

Relationships: `org_id` is a soft ref to `organization.id`, no FK (see the column). Primary key (`org_id`); **no secondary index, deliberately** — the whole table is the set of promotions in flight, which is one row on the busiest day ADR 0010 §8 Q3 anticipates, and every read is by primary key. Constrained by `tenant_freeze_ends_after_it_starts`, since a freeze that expires before it begins reads as "not frozen" to every consumer. Read by `QueueSignals.claim` (the queue half of the pause) and by every per-tenant job that asks `TenantFreezes.isFrozen`; it is the half of the promotion freeze that `maintenance_window` cannot express, because a RESTRICT window gates HTTP org paths only. ADR 0010 §6 hop 0→1, V58; `shared.tenancy.promotion.TenantFreezes` owns every statement against it.

### tenant_placement
Where each tenant's rows live, on which datasource, and whether that home is fit to serve. One row per organization, written before the tenant is announced and read on the way to deciding whether to serve it.

**Tier:** platform — it answers *which schema is this tenant in*, so it cannot itself be in the tenant's schema; and a fan-out that visits every schema has to be able to enumerate them from one place. It is also the one table an extracted tenant's new deployment writes fresh rather than receiving a copy of: a lifted tenant has exactly one placement, its own.

| Column | Type | Null | Description |
|---|---|---|---|
| org_id | uuid | no | `organization.id`, and the primary key — one tenant has exactly one home. Soft ref, no FK: the key would cross a module boundary (AGENTS §1), and the row has to be able to survive its organization, since a hard-deleted tenant still has bytes in a schema somebody must reclaim |
| schema_name | text | no | `tenant_pool` while pooled, `t_<32hex>` once promoted. The NAME rather than an "is siloed" boolean, because a restore target is a name and a boolean would have to be re-derived into one at every use |
| datasource_name | text | no | Which database serves this tenant; always `primary` until ADR 0010 Phase 7 keys `AbstractRoutingDataSource` on it. Written now at not-null with a default, so that hop is a value change rather than a migration against a table the request path reads |
| state | text | no | `PROVISIONING` (home claimed, not yet proven fit, **and not yet announced**), `ACTIVE` (schema at the recorded version, tenant announced — the only state that licenses serving), `FAILED` (attempted and unfinished; `last_error` says why). Constrained by `tenant_placement_state_known`; a fourth value is an expand step in its own release |
| schema_version | text | yes | The tenant sequence's head as last applied to `schema_name`, and what ADR 0010 §4.4's floor check compares `MIN_TENANT_SCHEMA_VERSION` against. A property of the SCHEMA denormalized onto every tenant in it, so moving the pool is one `update … where schema_name = ?` rather than a join on the request path. **NULL means "not yet recorded", never "behind"** |
| last_error | text | yes | Why the last provisioning or migration attempt failed, verbatim; NULL on every success. This is what makes a FAILED row worth having instead of sending its reader back to the logs |
| updated_at | timestamptz | no | When the state last changed. Not `created_at`/`updated_at`: nothing cares when a placement was first recorded and everything cares how long a tenant has been stuck, which is `now() - updated_at` |

Relationships: `org_id` is a soft ref to `organization.id`, no FK (see the column). Primary key (`org_id`); indexed by (`schema_name`) for the fleet's by-schema statements, and by (`state`, `updated_at`) **partial on `state <> 'ACTIVE'`** for "which tenants are not fit to serve, oldest first" — the alert query, kept off the write path for the overwhelming ACTIVE majority. No `version`, `created_at` or `deleted_at`: every write is a single conditional statement whose row count is the answer, so there is nothing for optimistic locking to arbitrate, and a placement is never deleted while its schema holds bytes. ADR 0010 §4.2, V57; `shared.tenancy.placement.TenantPlacements` owns every statement against it.

### tenant_cutover
The tenants whose silo is moving to another **database** right now, and how far along each move is. One row per organization for the life of one cutover — from the first replicated byte to the day the stale source copy is dropped — and empty the rest of the time; a finished cutover's row is deleted, never marked done.

**Tier:** platform — it is read as a REFUSAL by the migration runner (DDL under a live publication crash-loops the subscriber and then silently under-copies, ADR 0011 §7.3) and by the promoter (`TenantPlacements.beginRelocation` declines a tenant a cutover holds), both of which walk schemas before any tenant axis exists; and a record of a move between two databases cannot live in either of them.

| Column | Type | Null | Description |
|---|---|---|---|
| org_id | uuid | no | `organization.id`, and the primary key — one live cutover per tenant, enforced structurally. Soft ref, no FK: V57's reasons, plus this row is the only record that replication objects exist on TWO databases and must be torn down — an orphaned slot pins WAL until a human drops it |
| schema_name | text | no | The silo being moved. Derivable from `org_id`, recorded so the runner's refusal is one lookup by the name it walks schemas under |
| source_datasource | text | no | Where the tenant is served from when the move begins, and where a rollback puts it back — recorded because the placement's `datasource_name` changes at the flip and the rollback needs the pre-flip answer afterwards |
| target_datasource | text | no | What the flip writes into `tenant_placement.datasource_name` |
| state | text | no | `SYNCING` (streams live, initial copy or catch-up; tenant fully served from the source), `CUT` (flip committed, reverse stream not yet confirmed — the one crash state needing operator judgement), `WATCHING` (reverse replication live; rollback is a brief freeze and one row). Constrained by `tenant_cutover_state_known` |
| started_at | timestamptz | no | When the move was recorded |
| cut_at | timestamptz | yes | When the flip committed; NULL while SYNCING. `cut_at + app.tenancy.cutover.watch-window` is what `decommission` refuses before |
| updated_at | timestamptz | no | When the state last changed |

Relationships: `org_id` is a soft ref to `organization.id`, no FK (see the column). Primary key (`org_id`); indexed by (`schema_name`) for the runner's refusal lookup. Constrained by `tenant_cutover_actually_moves` (`source_datasource <> target_datasource`). ADR 0011 §7.2, V60; `shared.tenancy.cutover.TenantCutovers` owns every statement against it, `shared.tenancy.cutover.TenantCutover` drives the sequence, and `docs/runbooks/tenant-cutover.md` is the operator's entry.

---

# signup

### signup_request
One self-service signup awaiting, or having completed, an email-verification handshake.

**Tier:** platform — the row exists before its tenant does; `org_id` is set only at completion.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| email | varchar(255) | no | Address to verify and to notify |
| org_name | varchar(80) | no | Name the requester wants for their organization |
| given_name | varchar(60) | yes | Requester's given name |
| family_name | varchar(60) | yes | Requester's family name |
| token_hash | varchar(64) | no | Hash of the emailed verification token; globally unique, and the plaintext is never stored |
| status | varchar(16) | no | PENDING or COMPLETED |
| expires_at | timestamptz | no | When the token stops working |
| created_at | timestamptz | no | When the signup was requested |
| completed_at | timestamptz | yes | When the handshake completed |
| org_id | uuid | yes | The organization this request created; NULL while pending |
| owner_person_id | uuid | yes | The person this request created; NULL while pending |

Relationships: `org_id` → `organization.id`; `owner_person_id` → `person.id` — both soft refs, no FK, both set only at completion.

---

# subscription

### org_subscription
Which plan one organization is on, and the commercial state of that arrangement.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant subscribed; one live subscription per tenant |
| plan_id | uuid | no | The plan whose entitlements apply |
| status | varchar(20) | no | ACTIVE, TRIALING, PAST_DUE, CANCELLED or PAUSED (read-only after a lapsed trial) |
| current_period_end | timestamptz | yes | End of the paid period; NULL = evergreen |
| trial_ends_at | timestamptz | yes | The instant a trial lapses; set while TRIALING |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the subscription began |
| created_by | uuid | yes | Who assigned it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete; frees the tenant's slot |

Relationships: `plan_id` → `plan.id` (soft ref, no FK — V53 cut it: `plan` is the platform's price list and this row is the tenant's, so the two are destined for different databases even though both tables sit in this module); `org_id` → `organization.id` and `created_by` / `updated_by` → `person.id` (soft refs, no FK). Unique among live rows: (`org_id`).

### plan
One commercial tier organizations can be placed on. Seeded reference data.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| code | varchar(30) | no | The code the platform and API use; globally unique |
| name | varchar(100) | no | Display name |
| rank | int | no | Upgrade ordering, lowest tier first |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the plan was seeded |
| created_by | uuid | yes | Who seeded it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |

Relationships: referenced by `plan_entitlement.plan_id` (**real FK**, cascade — also platform-tier, so it stays). `org_subscription.plan_id` is a soft ref since V53. Not soft-deletable — a plan is vocabulary, not a user aggregate.

### plan_entitlement
One thing a plan grants: a feature switched on, or a numeric cap.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| plan_id | uuid | no | The plan granting it; half the primary key |
| ent_key | varchar(60) | no | What is granted; half the primary key |
| limit_value | bigint | yes | A positive value caps a count; `-1` marks a plain feature that is simply on. An absent row means the feature is off, or the limit unlimited |

Relationships: `plan_id` → `plan.id` (**real FK**, cascade delete). Primary key (`plan_id`, `ent_key`).

---

# support

### org_sla_override
One organization's own SLA targets for one priority, replacing the seeded default.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant the override applies to; unique with `priority` |
| priority | varchar(2) | no | P1, P2, P3 or P4; unique with `org_id` |
| first_response_minutes | int | no | Minutes allowed before a first response is due |
| resolution_minutes | int | no | Minutes allowed before resolution is due |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the override was agreed |
| created_by | uuid | yes | Who set it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |

Relationships: `org_id` → `organization.id` (soft ref, no FK); `created_by` / `updated_by` → `person.id` (soft ref, no FK). Unique constraint (`org_id`, `priority`) — total; clearing an override is a real delete.

### sla_policy
The platform-default response and resolution targets for one ticket priority. Seeded reference data.

**Tier:** platform

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| priority | varchar(2) | no | P1, P2, P3 or P4; globally unique |
| first_response_minutes | int | no | Minutes allowed before a first response is due |
| resolution_minutes | int | no | Minutes allowed before resolution is due |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the policy was seeded |
| created_by | uuid | yes | Who seeded it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |

Relationships: `created_by` / `updated_by` → `person.id` (soft ref, no FK).

### ticket
One support request a tenant opened, and its progress against its SLA.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant that opened it |
| opener_person_id | uuid | no | The person who opened it, a member of this tenant |
| subject | varchar(200) | no | The ticket's title |
| category | varchar(40) | yes | What area the request is about |
| priority | varchar(2) | no | P1, P2, P3 or P4 — selects which SLA targets apply |
| status | varchar(25) | no | OPEN, IN_PROGRESS, WAITING_ON_CUSTOMER, RESOLVED or CLOSED |
| assignee_person_id | uuid | yes | The platform operator handling it, who is a member of no tenant |
| first_response_at | timestamptz | yes | When the first response was actually sent |
| first_response_due_at | timestamptz | no | When the first response is due |
| resolution_due_at | timestamptz | no | When resolution is due |
| escalated | boolean | no | Whether the breach job has already escalated it |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the ticket was opened |
| created_by | uuid | yes | Who opened it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete |

Relationships: `org_id` → `organization.id`; `opener_person_id`, `assignee_person_id`, `created_by`, `updated_by` → `person.id` — all soft refs, no FK. Referenced by `ticket_message.ticket_id` (real FK, cascade).

### ticket_index
The cross-tenant OPERATOR queue, as one platform-side projection of every tenant's live tickets. It **renders; the tenant schema answers** — a tenant reading its own tickets never touches it, the operator's single-ticket routes read the tenant's own row, and no authorization decision may read it.

**Tier:** platform — `ticket` is the tenant's, so `GET /api/v1/admin/tickets` would otherwise be one keyset query per tenant home merged in Java. ADR 0010 §8 Q1 measured that merge at 1.29–1.39 ms per home, flat, i.e. **279 ms per operator page at 200 homes and linear in the fleet**; since `silo-per-org` became the default placement the home count is the organization count, which is what fired §5.1's trigger for this table.

| Column | Type | Null | Description |
|---|---|---|---|
| ticket_id | uuid | no | Primary key; `ticket.id`, unique fleet-wide because ticket ids are v4 UUIDs — which is what lets the operator's routes probe it with no org in the URL |
| org_id | uuid | no | The organization whose home holds the ticket; the key back into the tenant's own schema for the detail read |
| opener_person_id | uuid | no | The person who opened it |
| subject | varchar(200) | no | The ticket's title |
| category | varchar(40) | yes | What area the request is about |
| priority | varchar(2) | no | P1..P4 |
| status | varchar(25) | no | The queue's only filter (`?status=`) |
| assignee_person_id | uuid | yes | The platform operator handling it |
| escalated | boolean | no | Whether the breach job has escalated it |
| first_response_at | timestamptz | yes | When the first response was sent |
| resolution_due_at | timestamptz | no | When resolution is due |
| created_at | timestamptz | no | `ticket.created_at`; half the queue's keyset |

Relationships: `ticket_id` → `ticket.id`, `org_id` → `organization.id`, `opener_person_id` / `assignee_person_id` → `person.id` — all soft refs, and there is **no FK to `ticket` and cannot be**: the two are in different tiers, and since ADR 0011 not necessarily in the same database. Indexed by (`created_at desc`, `ticket_id desc`) for the unfiltered page, (`status`, `created_at desc`, `ticket_id desc`) for `?status=`, and (`org_id`, `ticket_id`) for the reconciler's per-organization arms. No `deleted_at`, `version` or `created_by`: the row's existence is the liveness statement and nothing here is an aggregate. Columns are exactly what the operator queue orders, filters and renders — anything else would be duplicated data with its own drift. `support.internal.TicketIndex` owns every statement against it; `support.internal.TicketIndexReconciler` replaces the missing constraint nightly, per ADR 0010 §8 Q2's rule that no projection ships without its reconciler, and here it is not a backstop for the delete path but *is* the delete path — nothing in the application soft-deletes a ticket, so every disappearance is raw SQL (`SoftDeletePurgeJob`, a dropped schema) that cannot know this table exists. ADR 0010 §5.1 / §8 Q2, V61.

### ticket_message
One message on a ticket, from the tenant or from the platform.

**Tier:** tenant — no `org_id` of its own; its tenancy is inherited through the ticket.

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| ticket_id | uuid | no | The ticket this message belongs to; the message's tenancy is inherited through it |
| author_person_id | uuid | no | Who wrote it — a tenant member or a platform operator |
| body | text | no | The message text |
| internal | boolean | no | A platform-only note never shown to the tenant; a visibility flag, not a statement about the author |
| created_at | timestamptz | no | When the message was posted |

Relationships: `ticket_id` → `ticket.id` (**real FK**, cascade delete — same module); `author_person_id` → `person.id` (soft ref, no FK).

---

# webhooks

### webhook_delivery
One attempt to deliver one event to one subscription.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| subscription_id | uuid | no | The subscription being delivered to |
| org_id | uuid | no | The tenant that owns it, denormalised from the subscription |
| event_type | varchar(80) | no | Which event is being delivered |
| payload | text | no | The signed JSON body sent |
| status | varchar(20) | no | PENDING, PROCESSING, DELIVERED or FAILED |
| attempts | int | no | Delivery attempts made so far |
| max_attempts | int | no | Attempts allowed before the delivery is abandoned |
| next_attempt_at | timestamptz | no | When a worker may next claim it |
| locked_at | timestamptz | yes | When the current worker claimed it |
| last_error | varchar(1000) | yes | Why the last attempt failed |
| response_status | int | yes | HTTP status the endpoint returned |
| created_at | timestamptz | no | When the delivery was queued |
| delivered_at | timestamptz | yes | When it succeeded |

Relationships: `subscription_id` → `webhook_subscription.id` (**real FK**, cascade delete — same module); `org_id` → `organization.id` (soft ref, no FK). A log, not an aggregate: trimmed by retention rather than soft-deleted.

### webhook_subscription
One tenant-configured endpoint wanting a set of events.

**Tier:** tenant

| Column | Type | Null | Description |
|---|---|---|---|
| id | uuid | no | Primary key |
| org_id | uuid | no | The tenant that owns the subscription |
| url | varchar(2048) | no | The caller-supplied target, guarded against SSRF at send time |
| secret | varchar(200) | no | HMAC-SHA256 secret each payload is signed with |
| event_types | text | no | Comma-joined event codes this endpoint wants |
| status | varchar(20) | no | ACTIVE or DISABLED |
| version | bigint | no | Optimistic-locking counter |
| created_at | timestamptz | no | When the subscription was created |
| created_by | uuid | yes | Who created it |
| updated_at | timestamptz | yes | When it last changed |
| updated_by | uuid | yes | Who last changed it |
| deleted_at | timestamptz | yes | Soft delete |

Relationships: `org_id` → `organization.id` (soft ref, no FK); `created_by` / `updated_by` → `person.id` (soft ref, no FK). Referenced by `webhook_delivery.subscription_id` (real FK, cascade). No unique key: a tenant may point two subscriptions at the same URL.
