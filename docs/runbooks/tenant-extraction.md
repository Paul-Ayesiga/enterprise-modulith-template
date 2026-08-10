# Runbook — produce an extraction bundle for one organization

Not alert-driven. You are here because one organization is leaving the shared deployment: onto its own
database (ADR 0010 §6 hop 1→2) or its own deployment (hop 2→3), or because you are rehearsing that
move. **Extraction is operator-initiated only** — nothing schedules it and there is no HTTP route,
for the same two reasons the tenant extraction has never had one: the output carries live credentials,
and it is unbounded. Neither is fixable by adding a cap or a redaction, because that would just be the
GDPR disclosure bundle again, which is a different artifact for a different reader.

Read [ADR 0010](../adr/0010-schema-based-multi-tenancy.md) §6 first if you have not, and
[tenant-promotion.md](tenant-promotion.md) if the tenant is still pooled — the bundle is takeable
from `tenant_pool`, but a tenant that is leaving usually gets its own schema first.

The one-line version: **`pg_dump -n t_<hex>` is one of eleven items, and the other ten are the ones
that fail silently.**

---

## What the bundle is

`compliance.internal.TenantBundler.bundle(orgId, mode, sink)` produces ADR §6's eleven items and a
manifest that accounts for every one of them. The manifest cannot be constructed with an item missing —
an unreported item is indistinguishable from an item that produced nothing, and you are the only reader
who would ever have known the difference.

| # | Item | What actually happens |
|---|---|---|
| 1 | The tenant schema | `TenantExtractionService` streams every table in the tenant's own schema, read from the catalogue rather than from a list. You may also take this half by hand with `pg_dump -n t_<hex>` |
| 2 | `organization`, `external_organization` | seeded into the far side's own `platform` schema |
| 3 | Person projection | id, names, status and one **unverified** display contact, for every `person_id` the bundle references anywhere |
| 4 | Catalogue snapshot | `plan`, `plan_entitlement`, `sla_policy`, `translation`, `setting`, `feature_flag`, whole, **ids included** |
| 5 | `impersonation_session` | the org's rows, so its audit entries can resolve their stated reason |
| 6 | `event_publication`, `event_inbox`, `idempotency_key`, `shedlock`, `queue_signal`, `tenant_freeze`, `tenant_cutover` | **nothing.** The bundler has no SQL that could read them |
| 7 | Object store | **not produced here** — see below |
| 8 | `api_key` | REHEARSAL counts them; CUTOVER revokes them and reserves their prefixes forever |
| 9 | Identity provider | a recorded decision: federate to the platform issuer. `external_identity` is rewritten, never copied |
| 10 | `search_document` | rebuilt on the far side, not carried |
| 11 | `notification_delivery` | drained by the platform, not carried |

`BundleScriptWriter` turns that stream into one SQL file: `set search_path`, one transaction, the
inserts, then the manifest as trailing comments — including the parts whose answer is an absence and
which therefore have no statement anywhere above them to represent them.

### REHEARSAL and CUTOVER

Item 8 is destructive. `REHEARSAL` reports the keys a cutover **would** revoke and revokes nothing;
`CUTOVER` does it. Rehearse first, always: a rehearsal that killed the tenant's machine credentials is
a rehearsal nobody runs twice. The manifest records which mode it was, in every case — a bundle you
cannot tell apart from a rehearsal is a bundle whose keys you do not know the state of.

---

## Before you start

1. **Freeze the tenant.** A `CUTOVER` refuses an unfrozen one and says so; a `REHEARSAL` accepts it
   and warns. Both halves are needed and the promotion runbook's section on this applies verbatim: an
   org-scoped `maintenance_window` in `RESTRICT` mode gates HTTP org paths **only**, and
   `platform.tenant_freeze` is what stops the notification worker, webhook dispatcher, exchange
   runners, `OutboxResubmissionJob` and the retention purges from writing tenant rows while you read
   them.
2. **Drain the queues** (item 11). The bundler refuses on any of:
   - `platform.notification_delivery` rows PENDING or PROCESSING — these are **not** carried, so
     anything still queued when the tenant leaves is delivered by nobody;
   - `webhook_delivery` rows PROCESSING — a claimed row travels in the dump and the far side's
     stale-lock reclaim runs it again, so the same webhook is POSTed twice from two deployments;
   - `exchange_job` rows VALIDATING or PROCESSING — same shape, resumed on both sides.

   PENDING rows of the last two are **fine**: they travel and run once, on the far side.
3. **Legal hold — stop the extraction, not the hold.** The bundler *refuses* an org under a live
   `platform.legal_hold`, and it will not suggest releasing one. Whether a hold may be released is a
   decision for counsel and never a step taken to unblock a move; escalate, and bundle this tenant
   once the hold is lawfully gone.

   The reasoning: a hold is **custody**, not data. Holds are platform-tier because there must be
   exactly one set of them, so a copy cannot be the authority, and no copied row makes it lawful to
   hand held material to a deployment somebody else runs. The far side would also be running a
   `SoftDeletePurgeJob` that reads its own empty `legal_hold`, matches nothing, and hard-deletes the
   data the hold exists to keep — which `TenantSchemaSelfContainmentTest` now demonstrates directly:
   the hold does not travel, the aged membership *is* purgeable over there, and a hold written into
   the extracted deployment's own table stops it again. That is the hazard the refusal exists for, not
   an argument for carrying the row.

   (This used to disagree with itself. The bundler's message read "release the hold, or do not extract
   this tenant" while that same test asserted the opposite — that holds travel — and both shipped
   green. ADR §7 Phase 6 records the episode and the resolution.)
4. **Check the catalogue.** The bundler refuses if `plan`, `plan_entitlement` or `sla_policy` is
   empty, and refuses if any of the org's subscriptions names a plan `platform.plan` does not hold.
   See *The failure that has no error* below for why that second one is the check that matters.

Everything above is enforced. You do not have to remember it; the bundle declines to exist and names
what is wrong. This list is here so you know what you are being told when it does.

---

## Taking it

The bundler is not on an HTTP route, and — read this before you plan an extraction — **there is no
operator entry point yet.** `TenantBundler` has exactly one caller today and it is the test suite
(`compliance.internal.TenantBundleTest`, and `TenantSchemaSelfContainmentTest` through
`TenantBundleArtifact`). Building that entry point is a decision about **who may run an extraction**
before it is one about plumbing, because a bundle reads every secret the tenant owns; until it is
made, taking a real bundle means writing the caller as part of the change that needs it.

What the caller has to do is fixed, and the tests above are the worked examples:

- run it on the **platform** axis, outside any transaction (it refuses both otherwise);
- write the stream through `BundleScriptWriter` to a file;
- run the object-store extractor and call `manifest.withObjectBytes(...)` with what it took;
- call `manifest.requireComplete()` and keep its output with the file.

### Item 7 is a separate tool, on purpose

The bundler does **not** copy the object store. `compliance.internal.TenantObjectExtractionService`
does, over `doc/o/<orgId>/` and `exch/o/<orgId>/` — the complete set of org-scoped prefixes, verified.
The manifest carries those prefixes and **refuses `requireComplete()` until somebody reports taking
the bytes**, because a bundle whose bytes were never taken restores into a tenant that boots, lists
its documents, and 404s every download. A count of zero is a legitimate answer; silence is not.

Note that promotion (hop 0→1) copies no bytes at all — a key names the organization, not the schema —
so the object store moves only when the deployment does.

---

## Restoring it

The destination runs **both** migration sequences first — `db/migration/platform` into `platform`,
`db/migration/tenant` into its tenant schema — and then takes the script:

```bash
psql -v ON_ERROR_STOP=1 -f bundle-<orgId>.sql
```

The schema half comes from the migration set and never from a dump, deliberately: a dumped schema
arrives carrying the **source's** `flyway_schema_history`, which is another schema's applied-version
record wearing this one's name. And `ON_ERROR_STOP=1` matters — the script wraps itself in one
transaction, so with it a failure leaves an empty database, and without it a failure leaves a tenant
missing whichever tables came after it.

The script's `set search_path to tenant_pool, ext;` is the one line you may need to edit: an extracted
deployment serves exactly one tenant and needs no silo, but if yours places its tenant in a `t_<32hex>`
schema, that is where the line points. **Edit it before running, not after** — and edit only it. The
script's last statement before `commit` writes the placement row from `current_schema()`, so the
registry follows that line wherever you point it. That coupling is deliberate; see below for what it
is protecting you from.

### The routing row, which is the one that makes the rest reachable

`platform.tenant_placement` is what `TenantRoutes` asks to turn an organization into a schema, and
**an organization it holds no row for routes to `tenant_pool`**. On the platform that is the right
answer — it is what every unpromoted tenant gets. On the far side it is the worst outcome in ADR 0010
§1, because the destination ran both migration sequences and therefore *has* a `tenant_pool`, empty:

- every read is addressed to that empty schema, so the tenant logs in and presents itself as an
  organization with no members, no tickets and no history;
- every write lands in a schema whose rows disagree with their own `org_id`;
- nothing logs at any layer, because nothing is malfunctioning — each one is obeying a registry that
  was never written to.

Since `silo-per-org` became the default placement policy, that is the shape of *every* extraction
rather than an edge case. The script therefore writes the row itself rather than leaving it to this
document. If you restore by some other route — replaying a hand-taken `pg_dump` without the script,
say — write it yourself before starting the jar:

```sql
insert into platform.tenant_placement (org_id, schema_name, datasource_name, state, updated_at)
values ('<orgId>', '<the schema you restored into>', 'primary', 'ACTIVE', now());
```

`schema_version` stays NULL on purpose: V57's header says NULL means *not yet recorded*, never
*behind*, `TenantSchemaFloor` serves an unknown version rather than 503ing it, and the destination's
own migration runner fills it in on its next pass. Copying the source's version would be recording a
fact about another installation's schema.

### After the restore, before serving anybody

Do these in order. The first two are the ones that decide whether anybody can reach the tenant at all;
the rest decide what they find when they do.

1. **Confirm the tenant routes.** One row in `platform.tenant_placement`, `ACTIVE`, naming the schema
   you actually restored into. The script writes it; check it anyway, because every other check below
   passes against a deployment that cannot address its own tenant:

   ```sql
   select org_id, schema_name, state from platform.tenant_placement;
   ```

   One row, its own. If `schema_name` is `tenant_pool` and you restored into a silo, stop — the jar
   will serve an empty organization out of the empty pool and say nothing.

2. **Rebuild the membership index.** `platform.org_membership_index` is REBUILT, not carried, and the
   only thing that rebuilds it is `OrgMembershipIndexReconciler` at 04:50. Until it runs,
   `GET /api/v1/me/organizations` — the one person-first read in the codebase, and the list a client
   renders to offer the org switch — returns an empty list for every member. They are in the
   organization; they cannot see that they are. Do not wait for the cron:

   ```sql
   insert into platform.org_membership_index as i (person_id, org_id, status)
   select m.person_id, m.org_id, m.status from <tenant schema>.membership m
    where m.deleted_at is null
   on conflict (person_id, org_id) do update set status = excluded.status
    where i.status is distinct from excluded.status;
   ```

3. **Mint new API keys** and give them to the tenant. The old ones are dead and their prefixes are
   burned on the platform forever.
4. **Rebuild the search index** (item 10) — `search_document` arrives empty and nothing rebuilds it on
   its own.
5. **Configure an integration.** If the org had no `integration` override of its own it was running on
   the platform default, which holds the *platform's* provider credentials and stays behind. The
   manifest warns when that is the case.
6. **Expect the person graph to drift.** Members are a projection: names, status, one unverified
   contact. Writes to `person`, `person_contact` and `external_identity` stay platform-only forever,
   because two global uniqueness constraints cannot be enforced from a silo.
7. **Tell members their personal documents did not come.** A personal document belongs to a human, not
   to the org, and a human in three orgs has one document store. `/api/v1/documents` is empty for
   every member of the extracted deployment, correctly.

---

## The failure that has no error

ADR §6 calls item 4 the worst trap in the document, and the version that actually happens is not the
one it describes.

The described version: with no plan resolvable, `EntitlementResolver` returns an empty map;
`limitOf` returns null for every key and `requireWithinLimit` does nothing when the limit is null, so
**every quota is unlimited**; in the same map `hasFeature` is `containsKey` and is false for every
key, so **every `requireFeature` 403s**. One missing table removes every ceiling and closes every door
at once, and logs neither.

The version you will actually meet: an extracted deployment does **not** boot with an empty `plan`
table, because `PlanSeeder` is an `ApplicationRunner` and creates FREE / PRO / ENTERPRISE on every
start — with *new UUIDs*. The tenant's `org_subscription.plan_id` travelled in the dump and now
matches nothing, `resolve` falls through to `findByCode("FREE")`, and an ENTERPRISE tenant is served
the free entitlements. Not unlimited, not 403 — **downgraded**, with no error, no log line and no
failing request to investigate. `org_subscription.plan_id → plan.id` is one of the five foreign keys
ADR 0010 cut, so the database will not catch it either.

Two things stop it, and you should know both exist:

- the bundle carries the catalogue **whole, ids included**, and refuses to be taken from an
  installation where a subscription already names a plan that is missing;
- `subscription.internal.PlanCatalogGuard` re-asks the question on the far side at
  `ApplicationReadyEvent` and **fails startup** naming the plans. A deployment serving a silently
  downgraded tenant is worse than one that will not start, because the second gets fixed.

If startup fails with that message, the answer is almost always that the catalogue snapshot was not
applied. Restore it before serving anybody; do not re-seed.

**On the platform, the same guard only says it.** Failing startup is right for a deployment that
serves one tenant and cannot serve it correctly; it would be an outage for 4,999 healthy tenants if
one organization's `plan_id` dangled on the shared installation. So the guard refuses on `tenant_pool`,
refuses on a silo **only when `platform.tenant_placement` holds exactly one tenant** (which is the
extracted deployment), and otherwise logs a WARN naming the organization, the plans and the query. If
you are chasing a downgraded tenant on the platform, that WARN is what to grep for; the query it
prints is:

    set search_path to <the tenant's schema>;
    select s.org_id, s.plan_id from org_subscription s
     where s.deleted_at is null
       and not exists (select 1 from platform.plan p where p.id = s.plan_id);

---

## What this does not do

- It does not move the tenant. Promotion is [tenant-promotion.md](tenant-promotion.md); the database
  move is ADR §6 hop 1→2 (Phase 7, row-filtered logical replication).
- It does not copy the object store — item 7 is its own tool and its own acknowledgement.
- It does not decide *which* tenants get extracted. Nothing in this system does.
- It is not a GDPR disclosure. `GdprBundleService` is that artifact, and the two can never be one
  call: the disclosure must **not** contain `webhook_subscription.secret` or `api_key.secret_hash`,
  and an extraction that omitted them produces a tenant that does not boot.
- **It does not carry the usage ledger.** `TenantBundlePlan` leaves `platform.api_usage_daily` behind:
  copying it would give one billing period two ledgers that immediately diverge, and — the argument
  that settles it — every carried row arrives with `exported = false`, which is `UsageExportJob`'s half
  of the double-billing guard, so the far side would re-price days the platform has already invoiced.
  The far side starts its count at extraction and any in-period quota measured from it restarts. If
  billing continuity matters for the tenant in front of you, take the rows by hand and say so in the
  handover. (This was disputed in writing until the Phase 6 review; the self-containment test recorded
  the same table as `TRAVELS` for billing continuity. One judgement now, in `TenantBundlePlan`, gated by
  `ExtractionVerdictAgreementTest`.)

---

## What the rehearsal has and has not proven

Read this before you promise anybody a date. ADR §7 Phase 6's gate has two halves and only one of
them is closed.

**Proven, against real Postgres.** `pg_dump -n t_<hex>` restores into an empty database seeded with a
single-tenant `platform` schema, under `ON_ERROR_STOP=1`. Every row of every tenant table arrives, no
foreign key leaves the schema, a member's permissions resolve and a revoked member's do not
(AGENTS §5.5), a ticket opens against the restored SLA policy, and the real `TenantTierTables` planner
exports the whole tenant tier out of the restored schema alone. Separately, the bundler's own emitted
script restores into a second database built by both migration sequences, and the tenant's
subscription joins a `platform.plan` row on the far side.

**Not proven: the jar has never been booted against a restored database.** Both tests drive SQL. So
"the jar boots with the platform host unroutable" is still an assertion, and specifically:
`PlanCatalogGuard` — whose entire purpose is the far side — has never run against a restored
deployment, and neither has `TenantRoutes`. The routing gap this runbook now spends a section on
survived exactly because nothing ever asked a booted application where the tenant was. Treat the first
real extraction as the test, and do it in rehearsal mode with a spare database first.
