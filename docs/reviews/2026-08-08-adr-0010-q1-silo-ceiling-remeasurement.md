# ADR 0010 §8 Q1 — the silo ceiling, re-measured — 2026-08-08

ADR 0010 §8 Q1 says of the 200-silo ceiling: *"This is the one number in the document I would most
want re-measured before it is load-bearing."* Phase 5 made it load-bearing — `TenantPromoter`
refuses the 201st silo and quotes the derivation in its refusal message — so this is that
re-measurement, run exactly as §8 Q1 specifies it: **the UNION benchmark at N = 50 / 100 / 200
against real `tenant_pool` + silo data, not synthetic schemas.**

**The headline: the ceiling should stay at 200, and all three numbers the ADR derived it from are
wrong.** Planning per branch is overstated by 12–15×; locks per branch are understated by 3.5×; the
lock capacity both are measured against is 2.3× larger than assumed. They do not cancel, they point
at a *different constraint* — and the constraint that actually binds at 200 belongs to a query shape
the ADR never modelled, because Phase 5 shipped a per-home merge instead of the UNION. The full
verdict is in [ADR 0010 §8 Q1](../adr/0010-schema-based-multi-tenancy.md); this file is the raw
numbers and the method, so the next person can re-run it and diff.

---

## 1. Method

**Database.** A throwaway `postgres:18.4-alpine` container, defaults untouched except where noted:
`max_connections = 100`, `max_locks_per_transaction = 64` (the pair ADR §1 turns into "~6,400
cluster-wide slots"), `shared_buffers = 128MB`, `work_mem = 4MB`. Host: Darwin 25.5.0, aarch64,
Docker. **This is a laptop under a VM, not the box the original numbers came from** — which is why
every comparison below is a *ratio* between two measurements taken on the same box, never an
absolute against the ADR's absolutes.

**The fleet was built by the real migration path, not by hand.** `TenantMigrationRunner` off the
classpath — the same object `TenantMigrationJob`'s `main` runs in the Kubernetes Job — applied:

- the 44-file platform sequence into `platform` (727 ms), creating `platform`, `tenant_pool`,
  `ext`, `no_tenant`;
- the 33-file tenant sequence into `tenant_pool` (418 ms, 33 applied);
- the same tenant sequence into **200 real silo schemas** named `t_<32hex>` from a uuid exactly as
  `TenantSchemas` derives them, each with an `ACTIVE` row in `platform.tenant_placement`.

Fan-out: 8 workers, 22.8 s wall, **0 failures**, per-schema 688–1301 ms (avg 901 ms).

**Real rows, because empty schemas do not reproduce the planning cost the estimate is about.**

| table | `tenant_pool` | each silo | fleet total |
|---|---|---|---|
| `ticket` | 120,000 across **4,800 orgs** | 1,000 (one org) | 320,000 |
| `ticket_message` | 360,000 | 3,000 | 960,000 |
| `audit_log` | 120,000 | 1,000 | 320,000 |

5% of tickets are soft-deleted; status is spread over all five values; `created_at` is unique per
row within a home. `ANALYZE` was run per schema, so every branch is planned against real
statistics. Database: **716 MB**. Other tenant tables are empty — stated plainly because it matters:
the benchmark's branch relation is `ticket`, and `ticket` carries its full production index set
(see §3).

**The query is the real one.** `TicketRepository.pageForQueue`'s shape, unqualified in production and
schema-qualified per branch here: each branch is `select <the 18 mapped ticket columns> from
<home>.ticket where deleted_at is null and status = 'OPEN'`, the branches joined by `union all`, the
whole thing wrapped in `order by created_at desc, id desc limit 21`. Branch 1 is always
`tenant_pool`. Each branch resolves to an `Index Only Scan using idx_ticket_status_recent`, i.e. the
index V49 added for exactly this query. A second variant appends the keyset predicate
`(created_at, id) < (?, ?)` to every branch, which is page 2 of the cursor.

**Harness:** committed at [`perf/tenancy/`](../../perf/tenancy/) — `SiloCeilingBench`,
`SiloCeilingControls`, `LockCapacityProbe`, `DropSchemaCeilingProbe` — run with `javac`/`java`
against the resolved Gradle classpath plus `build/classes/java/main` and `build/resources/main`. No
Gradle task, no Spring context.

---

## 2. Planning time per UNION branch — the ADR's number is a **cold-backend** number

`EXPLAIN (ANALYZE, TIMING OFF, FORMAT JSON)`, "Planning Time" read from the JSON. Three regimes,
and the difference between them is the whole finding.

| branches | **cold backend** (first execution on a brand-new connection) | **warm backend** (same connection, run ≥ 2) | **server-side prepared** (`EXECUTE`, run ≥ 7) |
|---|---|---|---|
| 1 | 3.70 ms | 0.11–0.23 ms | 0.002 ms |
| 10 | 14.8 ms (1.48 /branch) | 0.29–0.36 ms (0.030 /branch) | 0.005–0.007 ms |
| 50 | 16.6–27.9 ms (**0.33–0.56** /branch) | 1.19–1.82 ms (**0.024–0.036** /branch) | 0.026 ms |
| 100 | 29.7–38.7 ms (**0.30–0.39** /branch) | 2.35–2.83 ms (**0.024–0.028** /branch) | 0.051–0.058 ms |
| 200 | 58.7–82.1 ms (**0.29–0.41** /branch) | 6.95–8.86 ms (**0.035–0.044** /branch) | 0.086–0.098 ms |

**ADR §5.1 / §8 Q1 claim:** ~0.5–0.66 ms per branch, *does not amortize*, "warm re-runs pay it
again"; 50 branches 12–24 ms, 200 branches 69–100 ms.

**Measured:** the ADR's per-branch figure reproduces **only on the first execution on a backend that
has never touched those relations**. The steady-state cost on a pooled connection is **12–15×
lower**, and under a server-side prepared statement it is **80–100× lower**. Planning amortizes
twice over: once per backend (relcache/catcache), and again per statement (plan cache).

Two controls make that unambiguous:

- **A3, fresh vs. same backend.** Ten consecutive executions on one physical connection at 200
  branches: run 1 = 57.4 ms, runs 2–10 = 6.95–8.42 ms. Three executions each on a *brand new*
  connection: 58.7, 70.9, 71.0 ms. The expensive part is per-backend first touch, not per-plan.
- **A4, pgjdbc's own `PreparedStatement` at the default `prepareThreshold=5`.** 200 branches:
  execution 1 = 97.5 ms, executions 2–5 ≈ 34–39 ms, executions 6–10 ≈ 31–32 ms (the server-side
  prepare kicking in). The 200-branch query text is **67,627 bytes** — pgjdbc's statement cache
  default is 5 MiB / 256 queries, so **it is not defeated at the ceiling**; ADR §5.1's claim that a
  fleet-sized query text "defeats both the JDBC statement cache and Postgres' plan cache" is false
  at 200 branches and only becomes true a long way past it.

Two smaller corrections while we are here. The keyset-cursor form (page 2 of the queue, A2) is
*cheaper*, not dearer: 200 branches, 8.2–12.0 ms planning / 2.3–4.3 ms execution. And query text
grows at **338 bytes per branch**, so 5,000 branches is ~1.69 MB, not the ~730 KB §5.1 states.

---

## 3. Locks per fan-out transaction — **7.000 per branch, not 2.004**

`BEGIN; EXPLAIN (ANALYZE) <union>; select count(*) from pg_locks where pid = pg_backend_pid();`
Empty-transaction baseline: 2.

| branches | total lock entries | of which `relation` | per branch |
|---|---|---|---|
| 1 | 9 | 8 | 7.000 |
| 10 | 72 | 71 | 7.000 |
| 50 | 352 | 351 | 7.000 |
| 100 | 702 | 701 | 7.000 |
| 200 | **1,402** | 1,401 | **7.000** |

By type at 200 branches: 1,401 × `relation`/`AccessShareLock` + 1 × `virtualxid`/`ExclusiveLock`.

**ADR claim:** 2.004 entries per branch; 50 → ~102, 200 → ~402, 500 → ~1,002.

**Measured: 3.5× that.** And the reason is exactly the one §8 Q1 warned about when it asked for
real data rather than synthetic schemas — **the planner takes `AccessShareLock` on a table *and on
every one of its indexes***, and the real `ticket` has six — `ticket_pkey`, `idx_ticket_org`,
`idx_ticket_sla`, `idx_ticket_deleted`, `idx_ticket_status_recent`, `idx_ticket_recent`.
1 table + 6 indexes = 7, exactly, at every branch count. A figure of 2.004 is what a table with
**one** index yields. The original measurement was not taken against this schema.

This makes the per-branch lock cost a **function of the fan-out's table**, not a constant of the
design: any future fan-out over a table with more indexes costs more, and adding an index to
`ticket` raises this number for every branch at once.

---

## 4. The lock wall is not `max_locks_per_transaction × max_connections`

ADR §1 derives a hard wall at ~3,200 branches from "6,400 lock slots ÷ 2.004 per branch". **Both
halves are too low** — the divisor by 3.5× and the dividend by 2.3× — so the errors partly cancel
and the quoted wall lands within 1.5× of the truth by coincidence. That is the worst kind of correct
number: right enough that nobody re-checks it, and built on two facts that are each wrong enough to
mislead the next derivation that reuses them (§5.12 is that derivation — see below).

**First observation:** at `max_locks_per_transaction = 10`, `max_connections = 100` — nominal 1,000
slots — a single transaction held **1,409** relation locks with no error. The documented product is
an *average across backends* used to size the shared lock table; it is not a per-transaction cap,
and the table can grow into leftover shared memory.

**So probe it directly** (`LockCapacityProbe`: one transaction, `select 1 from <table> limit 0` over
every table in `platform` + `tenant_pool` + the 200 silos, until it fails):

| `max_locks_per_transaction` | nominal slots | **measured single-transaction ceiling** | ratio |
|---|---|---|---|
| 10 | 1,000 | ~3,150 locks (failed after 712 tables, 3,112 counted at 700) | 3.1× |
| **64 (default)** | **6,400** | **14,751 locks** (failed on table 3,315) | **2.3×** |

Failure mode confirmed exactly as the ADR describes it: `ERROR: out of shared memory`, SQLSTATE
`53200`, whole transaction lost.

**Therefore the real UNION wall is ≈ 14,751 ÷ 7.0 ≈ 2,100 branches** on an otherwise idle cluster —
not the ADR's ~3,200, and not the ~914 a naive 6,400 ÷ 7.0 would give. **Do not design against
2,100.** It is a single-transaction figure on an idle cluster; the lock table is *shared*, so every
concurrent backend eats into it, and the honest planning number is the fraction of budget one
fan-out takes: at 200 branches, **1,402 entries = 22% of the nominal 6,400 and 9.5% of the measured
ceiling**.

### 4.1 The same error propagates to §5.12's `drop schema … cascade` ceiling

§5.12 states "15 schemas per transaction; 439 lockable relations per tenant schema", derived as
6400/439. Measured (`DropSchemaCeilingProbe`, rolled back, non-destructive):

| dropped in one transaction | locks held | per schema |
|---|---|---|
| 1 | 492 | 492.0 |
| 10 | 4,875 | 487.5 |
| 30 | 14,615 | 487.2 |
| **31** | — | **`ERROR: out of shared memory` (53200)** |

**30 schemas, not 15.** The per-schema lock count (487) is close to the ADR's 439; the capacity
figure is what was wrong. §5.12's *advice* — "One schema per transaction, always" — survives
untouched and is now known to be conservative by 2× rather than by nothing.

---

## 5. Total statement time at the ceiling, and what the shipped code actually costs

This is the part that changes the answer, because **`TicketFanOut` does not build the UNION.**
Phase 5 shipped an N-way merge in Java: the same one-home keyset query run once per home, each in
its own transaction, merged by `TicketFanOut.QUEUE_ORDER`. So the UNION numbers above describe a
statement this codebase never issues, and the honest comparison is between the two shapes.

| homes | **UNION**, warm, server-side (plan + exec) | **UNION** client round trip | **shipped per-home merge**, total | per home | max locks in any one transaction |
|---|---|---|---|---|---|
| 50 | 1.4 + 13.3 ≈ 15 ms | 19–21 ms | **76.9–103.2 ms** | 1.54–2.06 ms | 9 |
| 100 | 2.6 + 17.8 ≈ 20 ms | 27 ms | **129.6–145.8 ms** | 1.30–1.46 ms | 9 |
| 200 | 7.9 + 25.9 ≈ 34 ms | 42–45 ms | **258.3–278.6 ms** | 1.29–1.39 ms | 9 |

The UNION columns quote a representative warm run rather than a mean: **execution** time was noisy
on this box (a 50-branch warm run varied 13.3–46.6 ms across six executions), which is one more
reason the load-bearing comparison here is the per-home column, where three runs at each size agreed
to within 4% at 100 and 200. Planning was stable throughout.

Two things follow, and they pull in opposite directions:

- **`TenantFanOut`'s javadoc is right about locks.** The per-home shape holds **9** lock entries at
  any instant regardless of home count, against the UNION's 1,402 at 200. The 6,400-slot budget is
  genuinely never approached, exactly as claimed.
- **The per-home shape is ~7× slower per page.** 279 ms at 200 homes against ~40 ms for the UNION,
  and it is linear in homes with no plateau (1.29–1.39 ms per home is flat from 100 upward). ADR
  §5.1 budgeted "~130 ms of planning per query — acceptable for an operator listing" at 200 silos.
  **The shipped implementation costs 2× that budget, for a different reason than the ADR modelled,
  and it is a per-page cost on an interactive surface.** `SlaEscalationJob` pays a fan-out of the
  same shape once a minute.

---

## 6. Relation footprint per schema — **126 relations and 912 KB, not 239 and ~3.2 MB**

One silo, freshly migrated by the real runner, never written to:

| | measured | ADR §1 / §5.10 |
|---|---|---|
| tables in the schema | **28** | 55 |
| indexes in the schema | **98** | 184 |
| relations in the schema | **126** | 239 |
| toast tables + toast indexes it owns (in `pg_toast`) | 13 + 13 | not counted |
| `pg_class` rows per silo, all in | **152.8** (measured as a delta over 200 silos) | — |
| empty relation files, `sum(pg_total_relation_size)` | **933,888 B ≈ 912 KB** | ~3.2 MB |
| main+fsm+vm forks over tables and indexes | 827,392 B ≈ 808 KB | — |

**The 55/184/239 figures are the *uniform* schema-per-tenant numbers** — every table in every
schema — which is the design §1 rejected. Under the hybrid that shipped, a tenant schema holds only
the tenant tier: the tenant-only tables plus the tenant half of the seven split ones plus that
schema's own `flyway_schema_history`, **28 tables**, verified by listing `pg_class` in a silo. The
per-silo footprint is therefore 2.6× smaller than the ADR states.

Seeded silo (1,000 tickets / 3,000 messages / 1,000 audit rows): 2,383,872 B ≈ 2.27 MB.
`tenant_pool` (120k/360k/120k over 4,800 orgs): 153,354,240 B ≈ 146 MB. `platform`: 1,392,640 B.

Scaled:

| silos | empty relation files | `pg_class` rows |
|---|---|---|
| 200 (the ceiling) | **178 MB** (ADR: ~650 MB) | 31,333 measured (ADR §5.10: ~48,000) |
| 5,000 | **4.35 GB** (ADR §1: ~16 GB) | ~765,000 (ADR §1: ~1.2M) |

§1's rejection of uniform schema-per-tenant survives — 4.35 GB of empty files and 765k `pg_class`
rows at 5,000 is still not shippable — but the magnitude is ~2.6× smaller than the ADR asserts, and
anyone re-opening that decision should re-open it against these numbers.

---

## 7. Catalog size per schema — ~542 KB, ~2.65 GB at 5,000

Baseline is `platform` + `tenant_pool` with **zero** silos, taken by the same run before the silos
were created; the 200-silo row is the same query after.

| catalog | 0 silos | 200 silos | **per silo** | extrapolated to 5,000 |
|---|---|---|---|---|
| `pg_class` rows | 781 | 31,333 | 152.8 | ~765,000 |
| `pg_attribute` rows | 5,005 | 160,378 | 776.9 | **~3.89M** (ADR §1: ~6M) |
| `pg_index` rows | 424 | 22,735 | 111.6 | ~558,000 |
| `pg_depend` rows | 3,280 | 127,900 | 623.1 | ~3.12M |
| `pg_type` rows | 757 | 12,013 | 56.3 | ~282,000 |
| `pg_constraint` rows | 699 | 47,331 | 233.2 | ~1.17M |
| `pg_attrdef` rows | 30 | 2,844 | 14.1 | ~70,000 |
| catalog bytes | 9,158,656 (8.7 MB) | 120,217,600 (114.6 MB) | **~542 KB** | **~2.65 GB** (ADR §1: 5–6 GB) |

Same story: the shape of §1's argument holds, the magnitude is about half what it says, because the
tenant tier is 28 tables and not 55.

---

## 8. JDBC metadata per JVM start — 287–527 ms at the ceiling, not 9 s

pgjdbc `DatabaseMetaData.getColumns(null, null, "%", "%")` — the *unfiltered* call ADR §4.4 says
Hibernate's `GroupedSchemaValidatorImpl` issues when `hibernate.default_schema` is unset:

| | rows | ms |
|---|---|---|
| unfiltered, 201 tenant schemas, fresh connection ×4 | **65,208** | **526.5 / 371.9 / 323.5 / 287.4** |
| filtered to `tenant_pool` | 312 | 12.7 |
| filtered to `platform` | 377 | 7.8 |

312 rows per tenant schema. At 5,000 silos that is ~1.56M rows (ADR §1: ~3.1M — the uniform number
again) and, at the measured ~4.5–8 µs/row, **~7–12 s**, which does bracket the ADR's ~9 s.

At the actual ceiling of 200 it is **under 530 ms once, at boot** — and moot regardless, because
`ddl-auto` is already `none` and `MappedSchemaValidator` does the check after
`ApplicationReadyEvent` on two pinned axes (`platform` and the pooled tenant), issuing no unfiltered
metadata call at all. §4.4's decision was right; its urgency was overstated by an order of magnitude
at the fleet size we actually allow.

---

## 9. Two other ADR numbers this run happened to touch

- **§4.2, "full replay into a fresh schema is ~200–330 ms and flat."** Measured here: 688–1301 ms
  per schema (avg 901) for the 33-file tenant sequence with 8 workers, and 22.8 s wall for 200
  schemas. Slower than the ADR — but this is a different box under a VM, the run was parallel, and
  the *flatness* claim holds (no degradation from schema 1 to schema 200). Not a contradiction, and
  not comparable.
- **§5.11, "`pg_dump -n <one schema>` is catalog-bound — 2.9 s / 86 KB for an empty tenant schema at
  300 schemas."** Measured at 200 schemas, one *seeded* silo: **2.16 s cold, 0.29–0.30 s warm**,
  1.04 MB; `--schema-only` 0.22–0.24 s / 48 KB. The same cold/warm split as §2. Compliance-export
  latency does not degrade with fleet size anything like as sharply as stated.

---

## 10. The pattern worth naming

**Every timing this run contradicts is contradicted the same way: it is a cold-cache,
first-execution measurement recorded as a steady-state cost.** UNION planning (0.5 ms/branch cold →
0.035 warm), `pg_dump -n` (2.9 s cold → 0.30 s warm), JDBC metadata (527 ms first → 287 ms fourth).
Every one of them reproduces on run 1 and collapses by run 2, so a benchmark that reported a single
execution reported the wrong number in the same direction three times.

**The two figures that moved the other way are not timings at all** — locks per branch (2.004 →
7.000) and lock-table capacity (6,400 → 14,751) — and each failed differently. The lock count was
measured against something that was not this schema, which is exactly the failure §8 Q1 anticipated
when it insisted on "real `tenant_pool` + silo data, not synthetic schemas". The capacity was never
measured at all: it was derived from a documented product that turns out to describe an average used
to size a table, not a cap. A number that arrives by arithmetic from two other numbers deserves the
same suspicion as one that arrives from a single benchmark run.

---

## 11. Reproducing this

The harness is committed at [`perf/tenancy/`](../../perf/tenancy/) with its own README: four
single-file Java programs run out of band. The suite is not involved and Gradle is never invoked.

Stand up the database with `docker run -d --name adr10q1-pg -e POSTGRES_PASSWORD=postgres -e
POSTGRES_USER=postgres -e POSTGRES_DB=smsone -p 35433:5432 --shm-size=1g postgres:18.4-alpine` —
port 35433 because this box already holds 5432, per the overrides in `docker/.env`. Compile the four
programs with `javac` against a classpath of the resolved Gradle artifacts plus
`build/classes/java/main` and `build/resources/main` (the migration scripts are read off the
classpath by `MigrationScripts`, so both are required). The artifacts are `flyway-core` 12.4.0 and
`flyway-database-postgresql`, `postgresql` 42.7.11, `spring-core` / `spring-jdbc` / `spring-beans`,
`HikariCP`, plus `jackson` 3 — flyway-core 12.4.0 needs `tools.jackson.databind`, and that in turn
needs `jackson-annotations` 2 — all already in `~/.gradle/caches`.

Then, in order: `SiloCeilingBench migrate 200` builds the fleet through the real
`TenantMigrationRunner`; `SiloCeilingBench seed` writes the 320k/960k/320k rows and analyzes them;
`SiloCeilingBench bench 1,10,50,100,200` is sections A–D; `SiloCeilingControls 50,100,200` is the
cold-versus-warm controls, the shipped per-home shape and the lock breakdown; `LockCapacityProbe`
probes the real single-transaction lock ceiling; `DropSchemaCeilingProbe` measures §5.12's
drop-schema ceiling and rolls back. `SiloCeilingBench footprint` reports §6–§8's numbers and should
be run **before** seeding for the empty-schema figures.

**One trap that cost a full re-run and belongs here:** a bulk text substitution injected a
`truncate` into the benchmark's per-home loop, which silently emptied every home *after* the lock
and planning sections had run and *before* the control sections did. The tell was
`pg_stat_user_tables.n_live_tup = 0` with `n_tup_del = 0` — a truncate, not a delete. Every number
above is from the clean re-run; assert row counts between benchmark sections, not only before them.

---

## 12. What this changes

Written up in full in [ADR 0010 §8 Q1](../adr/0010-schema-based-multi-tenancy.md). In one paragraph:
**keep `app.tenancy.promotion.max-silos` at 200 and re-derive it.** The two costs the ADR named —
UNION planning and lock slots — do not bind: planning amortizes to 7–9 ms at 200 branches and the
shipped fan-out issues no UNION at all, holding 9 locks whatever the fleet size. What binds is the
shape that actually shipped: `TicketFanOut`'s per-home merge at **1.33 ms per home**, i.e. **279 ms
per operator queue page at 200 silos**, growing linearly, on an interactive surface, with
`SlaEscalationJob` paying the same fan-out once a minute. That is 2× the ADR's own stated budget and
it is reached at 200 — so 200 remains the right ceiling by arithmetic that no longer resembles the
arithmetic it was chosen by, and §8 Q2's `platform.ticket_index` trigger at 50 silos (where the page
already costs 77–103 ms) is now the load-bearing item rather than a note for later.

**Not done here, and it needs doing.** Three pieces of production prose still carry the superseded
derivation verbatim, and this pass wrote no production code:

- `TenantPromotionProperties.DEFAULT_MAX_SILOS`' javadoc — "0.5–0.66 ms per UNION branch, paid on
  every execution", "2.004 entries per branch", "50 → ~102, 200 → ~402", "~48,000 relations and
  ~650 MB".
- `TenantPromoter.refuseAtTheCeiling`'s `TenantPromotionException` message — the same figures, shown
  **to an operator who has just been refused a promotion**. That message is the only place anyone
  reads this reasoning, which makes it the most important of the three.
- `TenantFanOut`'s class javadoc — same two figures plus the ~3,200-branch wall. Its *claim* is
  confirmed (the per-home shape never approaches the lock ceiling: 9 entries at any fleet size); the
  gap is that it says nothing about the 279 ms per page that shape costs at 200 homes, which is now
  the reason for the ceiling.
