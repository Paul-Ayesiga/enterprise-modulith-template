# Runbook — promote a tenant to its own schema (and demote it back)

Not alert-driven. You are here because somebody decided one organization should stop sharing
`tenant_pool` and get `t_<32hex>` of its own — noisy neighbour, an extraction that is being prepared,
or a rehearsal. **Promotion is operator-initiated only** (ADR 0010 §8 Q3): nothing schedules it, no
size or IOPS threshold triggers it, and there is no HTTP route for it. If you want automatic
promotion, the ADR's answer is "after ten successful manual ones, and never without the freeze
mechanism having proved itself at real data volume".

Read [ADR 0010](../adr/0010-schema-based-multi-tenancy.md) §6 first if you have not. The one-line
version: **the tenant's rows move between two schemas in the same database, and it is reversible by
copying them back and flipping one row.**

---

## What actually happens

`shared.tenancy.promotion.TenantPromoter` runs ten steps, and every one of them is ordered so that
its failure leaves the tenant serving correct rows from *some* schema:

| # | Step | If it fails |
|---|---|---|
| 1 | **Freeze**, three ways — see below | tenant untouched, both freezes lifted |
| 2 | **Settle** (`app.tenancy.promotion.drain`, 2 s) so in-flight requests finish | as above |
| 3 | **Create + migrate** the destination schema (`TenantProvisioner.materialize`) | as above; an empty schema is left, `reclaim()` drops it |
| 4 | **Copy**, one transaction, parents first, `INSERT … SELECT` per table | nothing committed |
| 5 | **Verify** — per-table row counts **and** checksums, in a *new* transaction | **aborts**; tenant still serves the source |
| 6 | **Flip** `platform.tenant_placement` — one compare-and-swap statement | nothing moved; destination holds a verified copy |
| 7 | **Evict** caches (`TenantPromotionCaches.evictAfterPlacementFlip`) | recorded as a warning; tenant is live |
| 8 | **Hold the freeze one `app.tenancy.route-ttl`** so every other pod re-reads the placement — see below | interrupt only; the flip stands and the source rows are still there |
| 9 | **Delete** the source rows, after re-checking the source still matches | rows kept, recorded as a warning |
| 10 | **Unfreeze** — runs in a `finally`, on every path | see *Recovery* |

### Step 8 exists because the flip is not a broadcast

Every process memoizes which schema a tenant lives in (`shared.tenancy.TenantRoutes`), because that
question is asked on **every connection borrow** and its answer changes once in a tenant's lifetime.
Step 7 drops that memo on the pod running the promotion and on **no other one**. Every other replica
re-reads within `app.tenancy.route-ttl` (30 s by default) and nothing pushes the flip to them.

So between the flip and that interval elapsing, another pod still addresses this tenant to the source
schema. That is harmless *only* while the freeze is up and the source rows are still there: a stale
pod reads rows that exist and are correct, and writes nothing, because the RESTRICT window stops HTTP
writes and `platform.tenant_freeze` stops the queues and jobs. Both of the steps that end that grace —
the source delete and the unfreeze — therefore come after the wait.

The promoter refuses to flip at all unless a whole route TTL still fits inside the freeze budget, so
a promotion that is running out of time aborts before the flip rather than during the grace. Lowering
`app.tenancy.route-ttl` shortens every promotion and costs one indexed read per tenant per interval
per pod; raising it does the reverse. **Do not change it between a flip and its grace** — the wait
reads the value the memos were written under.

### The freeze is three locks, not one

This is the part most likely to be got wrong, and ADR 0010 §6 says so in as many words: an org-scoped
`maintenance_window` in RESTRICT mode **gates HTTP org paths only**. The notification worker, webhook
dispatcher, exchange runners, `OutboxResubmissionJob` and the retention purges all write tenant rows
outside any request. So the promoter takes all three:

| Lock | Stops | Enforced at |
|---|---|---|
| `maintenance_window` (RESTRICT, org-scoped) | HTTP writes to `/api/v1/orgs/{id}/**` | `MaintenanceFilter` `@Order(4)` — 503 + `Retry-After`; **reads pass** |
| `platform.tenant_freeze` | the three durable queues, and any job that asks | `QueueSignals.claim` skips a frozen tenant; `TenantFreezes.isFrozen` for job loops |
| `tenant_placement.state = PROVISIONING` | every fan-out sweep | `TenantFanOut.fleet()` drops the tenant, and stands the pooled home down while it holds |

**The one writer none of them reaches** is a queue worker that leased this tenant one tick *before*
the freeze went on and is still running its batch. That is what the settle wait is sized against —
and when the sizing is wrong, step 5's fingerprint comparison catches it and aborts. Raise
`app.tenancy.promotion.drain` past the webhook queue's stale-lock window when promoting a tenant with
busy queues.

### Why verification is counts *and* checksums

A row count alone cannot see a row that *changed*, and it cannot see one row deleted and another
inserted while the copy ran. The checksum is
`sum(('x' || substr(md5(row::text), 1, 16))::bit(64)::bigint)` — order-independent, one pass, constant
memory, 64 bits per row, over the whole row through the composite type. Both sides are read inside
**one fresh transaction** (so `timestamptz` renders through the same session time zone) that is
**not** the copy's transaction (inside that one the comparison would be true by construction and would
prove nothing).

A mismatch is not a bug in the copy. It means a writer got past the freeze. **Find it before
retrying**, because a retry will race the same writer.

---

## Before you start

```sql
-- 1. The tenant must be ACTIVE and pooled.
select * from platform.tenant_placement where org_id = '<orgId>';

-- 2. Nothing else may be mid-move (a promotion stands the pooled sweep down for everyone).
select * from platform.tenant_freeze;
select * from platform.tenant_placement where state <> 'ACTIVE';

-- 3. How far below the ceiling are we? Default 200, app.tenancy.promotion.max-silos.
select count(*) from information_schema.schemata where schema_name ~ '^t_[0-9a-f]{32}$';

-- 4. How big is this tenant? Sizes the freeze budget. (~1.72 s per 150k rows, ADR §6.)
select (select count(*) from tenant_pool.ticket        where org_id = '<orgId>')
     + (select count(*) from tenant_pool.audit_log     where org_id = '<orgId>')
     + (select count(*) from tenant_pool.webhook_delivery where org_id = '<orgId>') as biggest_three;
```

Do **not** promote during a rolling deploy. Older pods do not read `platform.tenant_freeze` and their
workers will not honour the queue-side pause.

---

## Promote

`TenantPromoter` is a Spring bean with no HTTP surface. Drive it from wherever this deployment exposes
an operator console — a JVM debugger attached to one pod, a `@ShellComponent`, or a one-off `main`
against the same Spring context. It must run **outside a transaction** and returns a
`PromotionReport`.

```java
PromotionReport report = promoter.promote(orgId);

report.rowsMoved();      // the headline number
report.nonEmpty();       // per table: rows + checksum, both sides verified equal
report.freezeHeld();     // ← record this, the gate asks for it
report.warnings();       // MUST be empty before you walk away
```

**Record the freeze window in the table below.** ADR 0010 §7 Phase 5's gate is a measured number, not
an estimate, and the next promotion's budget is sized from the last one's.

| Date | Organization | Rows | Freeze held | Notes |
|---|---|---|---|---|
| _(fill in on the first real promotion)_ | | | | |

### Afterwards

```sql
select schema_name, state, schema_version from platform.tenant_placement where org_id = '<orgId>';
--    → t_<32hex> / ACTIVE / <head>
select count(*) from platform.tenant_freeze where org_id = '<orgId>';   -- → 0
select count(*) from tenant_pool.membership where org_id = '<orgId>';   -- → 0, the rows moved
```

Then drive one real request as a member of that org and watch it answer from the silo. If
`report.warnings()` mentioned a cache, restart the pods first: an eviction only reaches the process
that ran it plus whatever the L2 broadcast covers.

---

## Demote (reverse promotion)

Same ten steps, schemas swapped, destination not created. The silo is **not** dropped — that is a
separate, deliberate step.

```java
PromotionReport back = promoter.demote(orgId);   // t_<hex> → tenant_pool
// confirm the tenant is well, then:
promoter.reclaim(TenantSchemas.siloSchema(orgId));  // drops the now-empty schema
```

`reclaim()` refuses if any placement row still names the schema, or if the schema still holds any
rows at all. The second refusal is the one worth reading: rows in a silo whose tenant has left are
either a delete that did not finish **or a misrouted write** — ADR 0010 §1's worst failure — and they
are the only evidence of it. Read them before dropping anything.

One schema per transaction, always. A tenant schema holds ~439 lockable relations against ~6,400
cluster-wide slots, so the fifteenth `drop schema … cascade` in one transaction is the measured
ceiling and the sixteenth fails the whole block with "out of shared memory" (ADR §5.12).

---

## The ceiling: 200 silos per database

`app.tenancy.promotion.max-silos`, and the promoter refuses at it. The derivation, because you will
meet it in the refusal message:

- **Planning: ~0.5–0.66 ms per UNION branch, paid on every execution** — a query text that grows with
  the fleet defeats both pgjdbc's statement cache and Postgres' plan cache. Measured: 50 branches
  12–24 ms, 200 branches 69–100 ms, 500 branches 313–389 ms.
- **Locks: 2.004 entries per branch** against ~6,400 cluster-wide slots
  (`max_locks_per_transaction` 64 × `max_connections` 100). Measured: 50 → ~102, 200 → ~402,
  500 → ~1,002. Exhaust them and the transaction dies with "out of shared memory".
- **At 200: ~130 ms of planning and ~403 lock entries per fan-out transaction**, plus ~48,000
  relations and ~650 MB of empty relation files (ADR §5.10).

**Raising it is a measurement, not a decision.** ADR §8 Q1 asks for the UNION benchmark replayed at
N = 50 / 100 / 200 against real `tenant_pool` + silo data, not synthetic schemas. And note the lower
trigger: past **50** silos the intended answer is not a wider fan-out at all, it is
`platform.ticket_index` (§5.1, §8 Q2), after which the planning half of this derivation stops
applying and the ceiling should be re-derived from the lock half alone.

The fan-out deliberately does **not** enforce the ceiling — it logs at WARN. Truncating a sweep's home
list would silently stop doing the 201st tenant's work, which is the failure `TenantFanOut` exists to
prevent.

---

## Recovery — the promoter's process died

This is the only state that outlives a hard kill, and it is bounded by design:

```sql
select * from platform.tenant_freeze;                              -- reason, holder, expires_at
select * from platform.tenant_placement where state = 'PROVISIONING';
```

- **`expires_at` is in the future** — a promotion may still be running on another pod. `holder` names
  the process. Wait for it.
- **`expires_at` has passed** — the queues have already resumed for that tenant on their own; that is
  what the deadline is for. What has *not* self-healed is the placement row, and while it says
  `PROVISIONING` every fan-out sweep stands the pooled home down for the **whole fleet**. Fix it:

```sql
-- Which schema does the tenant actually have its rows in? Check both before deciding.
select count(*) from tenant_pool.membership where org_id = '<orgId>';
select count(*) from "t_<32hex>".membership  where org_id = '<orgId>';

-- Put it back in service in the schema that holds its rows (usually the source: the flip is one
-- statement, so a kill either happened before it or after it).
update platform.tenant_placement
   set state = 'ACTIVE', schema_name = '<the schema that has the rows>', updated_at = now()
 where org_id = '<orgId>';

delete from platform.tenant_freeze where org_id = '<orgId>';

-- And cancel the HTTP window, which has its own expiry but need not be waited out.
select id, ends_at, message from <that schema>.maintenance_window
 where org_id = '<orgId>' and deleted_at is null and mode = 'RESTRICT';
```

Then restart the pods (caches) and, if a half-built destination is left over, `reclaim()` it.

**If both schemas hold the tenant's rows**, the kill happened between the flip and the source delete.
The silo is authoritative — it is the copy that was verified — and `tenant_pool` holds the stale set.
Point the placement at the silo, confirm, then delete the pooled rows children-first.

---

## What this does not do

- It does not move a tenant to another **database**. That is ADR 0010 §6 hop 1→2 (Phase 7), a
  different mechanism (row-filtered logical replication) with a different reversal.
- It does not produce an extraction bundle. That is Phase 6 — `pg_dump -n t_<hex>` plus the ten other
  items in ADR §6.
- It does not decide *which* tenants deserve a silo. Nothing in this system does; see §8 Q3.
