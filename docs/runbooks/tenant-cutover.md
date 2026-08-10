# Runbook — move a tenant's silo to another database (and bring it back)

Not alert-driven. You are here because somebody decided one organization's silo should be served from
another Postgres — blast-radius isolation, a compliance region, the first step of a full extraction —
or because you are rehearsing that. **Cutovers are operator-initiated only** (ADR 0011 §10 Q6):
nothing schedules them, nothing triggers on size or load, there is no HTTP route, and the ADR's answer
to automation is "after ten manual cutovers that have each been through the reverse path at least
once, including one deliberate rollback — a reversal nobody has rehearsed is a reversal that does not
exist".

Read [ADR 0011](../adr/0011-own-database-tenants.md) §7 first. The one-line version: **logical
replication copies the silo online, so the freeze covers only drain → verify → flip — seconds,
whatever the tenant weighs — and until decommission a reverse stream makes flipping back cheap.**

Only a **silo** cuts over. A row-filtered publication under default PK replica identity breaks every
pooled tenant's UPDATE and DELETE fleet-wide at DML time (§7.1, measured), so the hop discipline is
0→1→2: [promote first](tenant-promotion.md), then cut over.

---

## What actually happens

`shared.tenancy.cutover.TenantCutover.moveToDatasource(orgId, name, links)` runs the §7.2 sequence.
The invariant the ordering serves: **the tenant keeps serving from the source until the flip commits,
and nothing between create-publication and flip is load-bearing for serving.**

| # | Step | Frozen? | If it fails |
|---|---|---|---|
| 1 | **Record** the move in `platform.tenant_cutover` (SYNCING). From here the migration runner and the promoter refuse this tenant | no | row deleted, nothing else existed |
| 2 | **Build the destination**: `TenantSchemaMigrator` (the same code as any silo), `ext`, `no_tenant`, the minimal `platform` holding exactly `event_publication` (§5.1's tripwire), emptiness proof, **flyway-history truncate** (see below) | no | streams + row torn down; retry freely |
| 3 | **Publish + subscribe**: `CREATE PUBLICATION p_<schema> FOR TABLES IN SCHEMA` on the source, `CREATE SUBSCRIPTION s_<schema> … WITH (copy_data = true)` on the destination | no | `DROP SUBSCRIPTION` takes its publisher slot with it; row deleted |
| 4 | **Sync**: every `pg_subscription_rel` at `r`, then catch-up past a captured LSN | no | stream keeps running, row stays SYNCING — **re-running resumes**, `abort()` tears down |
| 5 | **Freeze** — the promotion's three locks verbatim, then a 2 s settle | yes | unfreeze; stream keeps running; retry costs a new freeze |
| 6 | **Drain**: capture `pg_current_wal_lsn()` **once**, wait `confirmed_flush_lsn >=` it. Never a chase of the live head — the walsender decodes the whole fleet's WAL and the head moves with everyone's writes | yes | as above |
| 7 | **Verify** — per-table counts **and** checksums, across the two databases | yes | as above; a mismatch means a writer got past the freeze — find it first |
| 8 | **Flip** `tenant_placement.datasource_name` + cutover row → CUT, one transaction; evict caches; **hold the freeze one `app.tenancy.route-ttl`** | yes | before the flip: nothing moved. The flip itself is one transaction |
| 9 | **Reverse-replicate BEFORE unfreezing**: forward subscription dropped (loop prevention), destination publishes, source subscribes `WITH (copy_data = false)`; row → WATCHING | yes | see *stuck at CUT* below |
| 10 | **Unfreeze**; the reverse stream runs for `app.tenancy.cutover.watch-window` (P7D) | — | — |

Step 9's ordering **is** the rollback guarantee: the first write the destination ever serves is
already flowing back, so there is no instant at which flipping `datasource_name` back loses a
committed write. `copy_data = true` on the reversal was measured to storm `duplicate key` on every
synced table's PK — the origin still holds every row — which is why the reverse stream carries
changes only.

### The trap the transcript added: `flyway_schema_history`

`FOR TABLES IN SCHEMA` publishes the schema's Flyway history too, and the destination the migrator
just built holds its own copy — identical `installed_rank`s, because both sides ran the same
sequence. Tablesync's COPY then collides and the relation wedges in state `d`, **retrying every ~5 s
forever**, its sync slot pinning WAL on the source (measured: all 27 tier tables reached `r`, the
history table alone wedged the subscription). Step 2 therefore truncates the destination's history
before subscribing, and the source's rows arrive with the data. If you ever meet the wedge on a
hand-built destination: `truncate <schema>.flyway_schema_history` on the destination heals it in
place — the next tablesync retry succeeds.

### The freeze is the same three locks as a promotion

`maintenance_window` (RESTRICT, HTTP writes), `platform.tenant_freeze` (queues and jobs),
`tenant_placement.state = PROVISIONING` (fan-outs) — [tenant-promotion.md](tenant-promotion.md)
documents each. Two differences: the budget is a constant ten minutes (the frozen work no longer
scales with the tenant — that was the point), and the post-flip route-TTL hold protects only the
*write* half, because the source rows deliberately survive until decommission.

---

## Before you start

Prerequisites, in the order they will bite:

```sql
-- 1. wal_level must be 'logical' on BOTH databases. NOT settable at runtime — a restart
--    (docs/PRODUCTION.md §"Postgres settings": set it at provisioning, not at cutover).
--    The code refuses politely; this is the check it runs.
show wal_level;

-- 2. Slot and sender headroom on both sides (a cutover uses 1 durable slot per direction,
--    plus one transient sync slot per table during the initial copy).
select count(*) from pg_replication_slots;
show max_replication_slots;   -- compose/k3s ship 20, with max_slot_wal_keep_size=4GB

-- 3. The tenant: ACTIVE, siloed, on the datasource you think it is on.
select * from platform.tenant_placement where org_id = '<orgId>';

-- 4. Nothing else mid-move, no leftover cutovers.
select * from platform.tenant_cutover order by started_at;
select * from platform.tenant_freeze;
```

- **Privileges.** `CREATE PUBLICATION … FOR TABLES IN SCHEMA` and `CREATE SUBSCRIPTION` require
  superuser on their respective sides; the subscription's `CONNECTION` user needs `REPLICATION` on
  the database it dials.
- **The destination database is dedicated.** The builder refuses a destination whose `platform`
  schema holds anything beyond `event_publication` — anything more is somebody's platform database,
  and it would also disarm §5.1's wrong-database tripwire.
- **The datasource is configured**: `app.tenancy.datasources.<name>.url/.username/.password`
  (ADR 0011 §4.1) on every pod, one release before you route anything at it if you can.
- **The two conninfo strings** (`CutoverLinks`) are dialled by the *database servers*, not by the
  JVM: `sourceConninfo` is how the destination server reaches the source, `targetConninfo` the
  reverse. A host that is right in a JDBC URL (localhost port-forward, Docker-published port) may
  name nothing from the other server's network. In Kubernetes the service DNS names usually work
  from both; spell them fully: `host=pg-primary.db.svc port=5432 dbname=app user=repl password=…`.
- **Do not span a release.** The migration runner refuses this tenant for the life of the cutover
  row (§7.3), so a release that raises `MIN_TENANT_SCHEMA_VERSION` mid-window 503s the frozen tenant
  — the floor working, and still an outage someone scheduled. No rolling deploy during the freeze
  window either, for the promotion runbook's reason.

---

## Move

`TenantCutover` is a Spring bean with no HTTP surface; drive it the way you drive the promoter. It
must run outside a transaction and returns a `CutoverReport`.

```java
CutoverLinks links = new CutoverLinks(
        "host=pg-primary.db.svc port=5432 dbname=app user=repl password=…",   // destination → source
        "host=pg-analytics-eu.db.svc port=5432 dbname=app user=repl password=…"); // source → destination

CutoverReport report = cutover.moveToDatasource(orgId, "analytics-eu", links);

report.rowsMoved();     // verified identical on both databases, per table
report.syncing();       // the online half — free, scales with fleet WAL volume
report.freezeHeld();    // ← record this. ADR 0011 §7.2's claim is seconds, and this is the measurement
report.warnings();      // MUST be empty before you walk away
```

The online phase (steps 1–4) can be left mid-flight: a timeout or a kill leaves the stream syncing
and the row SYNCING, and **re-running `moveToDatasource` with the same arguments resumes it**.
`abort(orgId)` is the teardown if you change your mind — it drops both sides' objects and reports
anything an unreachable database kept it from dropping.

**Record the freeze window.** The next cutover's confidence is sized from the last one's number.

| Date | Organization | Rows | Synced in | Freeze held | Notes |
|---|---|---|---|---|---|
| _(fill in on the first real cutover)_ | | | | | |

### Afterwards

```sql
-- The placement now names the target; schema unchanged; cutover row WATCHING.
select schema_name, datasource_name, state from platform.tenant_placement where org_id = '<orgId>';
select state, cut_at from platform.tenant_cutover where org_id = '<orgId>';

-- The reverse stream, on the DESTINATION (it is the publisher now): active, confirming.
select slot_name, active, confirmed_flush_lsn, wal_status from pg_replication_slots;  -- on the target
```

Then drive one real request as a member of the org and watch it answer from the new database. During
the watch window, **replication lag on the reverse slot is an operator metric with a consequence**
(§8 item 4): an inactive slot means rollback is losing its cheapness and WAL is accruing on the
destination against `max_slot_wal_keep_size`.

---

## Rollback — during the watch window

Cheap by construction (step 9), and stays cheap until decommission:

```java
CutoverReport back = cutover.rollBack(orgId);   // freeze → drain the reverse slot → verify → flip back
```

Same freeze choreography, same drain-against-a-captured-LSN, same verification — in the other
direction. The destination's copy is **left in place**, stale from the flip-back, and the report's
warning names the disposal:

```java
cutover.reclaimAbandonedCopy(orgId, "analytics-eu");   // once you understand what prompted the rollback
```

`reclaimAbandonedCopy` refuses while any cutover row lives and refuses the datasource the tenant is
served from. If the rollback was prompted by a data defect, the stale copy is your evidence — reclaim
last.

---

## Decommission — after the watch window

```java
cutover.decommission(orgId);
```

Refused before `cut_at + app.tenancy.cutover.watch-window` — the window is the insurance term, and
shortening it is an ADR question, not a flag. What it does: drops the reverse subscription (source
side), both publications, then the stale source schema (`drop schema … cascade`, one schema per
transaction — ADR 0010 §5.12's measured lock ceiling), and deletes the cutover row. From here the
reversal is a fresh cutover the other way.

**The decommission trap, measured (§7.2 step 10):** `DROP SUBSCRIPTION` dials its publisher to drop
the slot. If the destination is unreachable it fails —

```
ERROR:  could not connect to publisher when attempting to drop replication slot "s_t_…"
```

— and the code takes the server's own escape (only for SQLSTATE class `08`, "the publisher could not
be asked"; anything else is rethrown with the slot still attached, because paying an orphaned slot
for a failure the escape does not repair is a WAL leak bought with nothing): `ALTER SUBSCRIPTION …
DISABLE` → `SET (slot_name = NONE)` → `DROP`. **The orphaned slot left on the far side keeps pinning
WAL there until somebody drops it.**

`decommission()` returns what it could not finish and sweeps for its own orphan once the cutover row
is gone, so a clean run needs nothing from you. When it comes back non-empty, or when a `moveToDatasource`
failure's suppressed exceptions name a slot, this is the pair of calls:

```java
cutover.reportAbandonedSlots("<datasource>");    // detect: inactive s_t_<hex> slots no in-flight cutover explains
cutover.reclaimAbandonedSlots("<datasource>");   // drop them; returns the ones actually dropped
```

`reportAbandonedSlots` also runs before every move, at ERROR, so a leak from a previous incident
surfaces at the start of the next one rather than at the disk-full alert. **`reclaimAbandonedSlots`
is safe against the database a tenant is being served from** — which `reclaimAbandonedCopy` refuses
to be, and the difference is what is destroyed: that one drops a schema full of rows, this one drops
a slot, which holds none. Two filters keep it off a live stream: an in-flight cutover's slots are
subtracted **by name** (so a WATCHING tenant's reverse stream is out of reach even during the seconds
its apply worker spends reconnecting), and an attached slot is `active`, which Postgres refuses to
drop at all (`55006`). It has to be safe there, because the two orphans that matter most — the
escape's, and a failed `CREATE SUBSCRIPTION`'s — both land on a database the tenant *is* serving from.

By hand, if you would rather see it yourself:

```sql
-- on the far side, once it is reachable again
select slot_name, active, wal_status from pg_replication_slots;
select pg_drop_replication_slot('s_t_<32hex>');
```

`max_slot_wal_keep_size` (4GB, compose and k3s both) bounds the damage; it does not remove the step.

### The four ways a slot is left behind

Each one ends at `reclaimAbandonedSlots`, and each is why it exists:

| where | what happened | who tells you |
|---|---|---|
| decommission | the reverse subscription's publisher (the destination — where the tenant now serves) was unreachable, so the escape ran | `decommission()`'s return value, after it tries the sweep itself |
| rollback / step 8 | the same escape on the forward or reverse subscription | `CutoverReport.warnings` |
| abort | a database was unreachable, so a drop could not finish | `abort()`'s return value — **empty is the only clean answer** |
| a failed build | `CREATE SUBSCRIPTION` created its slot on the publisher and then failed, so there is no subscription anywhere to drop it | the thrown exception's suppressed exceptions; the failure path also sweeps, since the row is gone by then |

---

## The abort story, per state

| `tenant_cutover.state` | Placement says | What happened | Do |
|---|---|---|---|
| SYNCING | source | building or syncing; nothing about serving changed | `abort()` any time, or just re-run to resume |
| SYNCING, freeze rows present | source, maybe PROVISIONING | died inside the freeze window, before the flip | freeze lapses on its own (V58); `abort()` puts PROVISIONING back to ACTIVE, drops the streams, deletes the row |
| CUT | **target** | flipped, reverse stream not confirmed | **stuck at CUT — read below** |
| WATCHING | target | fully moved, insured | `rollBack()` or, after the window, `decommission()` |
| WATCHING | source | a rollback flipped back but died before its teardown | `abort()` — it drops whatever streams remain and deletes the row |

### Stuck at CUT

The one state that needs judgement instead of a command. The tenant is **live on the destination**;
the reverse stream is not up; the code has deliberately left the freeze to lapse on its own deadline
rather than lifting it (the moment an unfrozen destination write lands, that write exists nowhere
else, and cheap rollback is gone). Decide before the freeze expires if you can:

- **Preferred — finish step 9 by hand**, then unfreeze and mark WATCHING:

```sql
-- on the destination (it still carries the forward subscription if the crash was early):
drop subscription if exists "s_t_<32hex>";           -- loop prevention first
create publication "p_t_<32hex>" for tables in schema "t_<32hex>";
-- on the source:
create subscription "s_t_<32hex>"
  connection '<targetConninfo>'
  publication "p_t_<32hex>" with (copy_data = false); -- copy_data=true here duplicates every PK
-- then, on the platform database:
update platform.tenant_cutover set state = 'WATCHING', updated_at = now()
 where org_id = '<orgId>' and state = 'CUT';
delete from platform.tenant_freeze where org_id = '<orgId>';
-- and cancel the RESTRICT maintenance_window on the org's (destination) schema, as in the
-- promotion runbook's recovery section.
```

- **Or accept**: let the freeze lapse, the tenant serves from the destination with **no reverse
  stream** — rollback from here is a fresh cutover in the other direction. Mark WATCHING anyway (the
  row must not sit at CUT; the migration-runner refusal keys on the row's existence either way) and
  write down that the insurance is gone.

---

## Reverse cutover — bringing a tenant home

The same method, the same machinery, the roles swapped — deliberately not a second implementation:

```java
// links with the conninfo roles reversed: source is now the remote, target the primary
cutover.moveToDatasource(orgId, "primary", new CutoverLinks(remoteConninfo, primaryConninfo));
```

The destination build recognises the primary and runs only the schema migrator (the real `platform`
is there and must not be touched); everything else — publication on the remote, subscription on the
primary, drain, verify, flip, reverse stream, watch window, decommission — is the sequence above
verbatim. A tenant must come home this way before it can be demoted to `tenant_pool`: the promoter
refuses a tenant on a non-primary datasource, because its `INSERT … SELECT` cannot span databases.

---

## A migration reached the source mid-window anyway

The runner refuses (§7.3), so this means someone applied DDL by hand or with an old binary. Both
failure signatures were measured; you will see the first in the destination's log:

1. **Apply worker crash-looping** every ~5 s: `logical replication target relation
   "t_….<new table>" does not exist`; the slot goes inactive; `confirmed_flush_lsn` freezes; WAL
   accrues on the source.
2. Creating the table on the destination **heals the stream and silently skips that table's rows** —
   the queued changes to other tables arrive in order, the new table stays empty. Green stream,
   complete-looking tenant, one table short.

The repair, in order, both halves mandatory:

```sql
-- on the destination: bring the schema to the same shape (the runner's fanOut for this datasource,
-- or the same DDL by hand), then:
alter subscription "s_t_<32hex>" refresh publication;   -- tablesyncs the missing table (measured)
```

Verify the table's counts against the source afterwards; the cutover's own step 7 will also refuse
the flip until they match.

---

## What this does not do

- It does not move `person`, the catalogs, the routing registry, queue signals, locks or freezes —
  those stay on the primary forever (ADR 0011 §3, §5). It moves the tenant tier, whole, and nothing
  else.
- It does not move SeaweedFS bytes, re-mint API keys, or produce a bundle — extraction is
  [tenant-extraction.md](tenant-extraction.md); this is hop 1→2, one deployment, many databases.
- It does not decide which tenants deserve their own database, and it does not run itself (§10 Q6).
