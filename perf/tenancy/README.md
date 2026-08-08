# perf/tenancy — the silo-ceiling benchmark (ADR 0010 §8 Q1)

Four single-file Java programs that build a real tenant fleet and measure what the silo ceiling is
actually made of. They exist because ADR 0010 §8 Q1 says the 200-silo ceiling is *"the one number in
the document I would most want re-measured before it is load-bearing"*, Phase 5 made it load-bearing
(`TenantPromoter` refuses the 201st silo), and the re-measurement contradicted most of the
derivation. See
[`docs/reviews/2026-08-08-adr-0010-q1-silo-ceiling-remeasurement.md`](../../docs/reviews/2026-08-08-adr-0010-q1-silo-ceiling-remeasurement.md)
for the findings and [ADR 0010 §8 Q1](../../docs/adr/0010-schema-based-multi-tenancy.md) for what
they changed.

Unlike the k6 suite next door, this does **not** run against the app. It talks to Postgres directly
and builds its fleet through the production `TenantMigrationRunner`, so the schemas it measures are
the schemas a promotion actually creates — §8 Q1's whole point being *"real `tenant_pool` + silo
data, not synthetic schemas"*.

## The programs

| program | what it answers |
|---|---|
| `SiloCeilingBench` | stages `migrate <n>` / `seed` / `bench <branch counts>` / `footprint`. Builds the fleet, fills it, then measures UNION planning and execution time, whether a server-side prepare amortizes the planning, lock entries per fan-out transaction, and the shipped per-home merge |
| `SiloCeilingControls` | the controls that separate "planning is expensive per branch" from "expensive the FIRST time a backend touches those branches" — fresh connection vs. reused connection, pgjdbc's own `PreparedStatement` at the default `prepareThreshold`, the clean per-home timing, and the lock breakdown by type |
| `LockCapacityProbe` | how many relation locks one transaction can really hold before `out of shared memory`. `max_locks_per_transaction × max_connections` is an average used to size the shared lock table, **not** a per-transaction cap, so this had to be measured |
| `DropSchemaCeilingProbe` | ADR §5.12's `drop schema … cascade` ceiling. Rolls back, so it is non-destructive |

## Running it

The whole run is about a minute of database work plus a few minutes of seeding. It never invokes
Gradle — `./gradlew test` takes nine minutes and none of it is relevant here — but it does need
`build/classes/java/main` and `build/resources/main` on the classpath, because
`MigrationScripts` reads the migration sequences off the classpath and `TenantMigrationRunner` is
the thing under measurement.

1. **A throwaway Postgres.** `docker run -d --name adr10q1-pg -e POSTGRES_PASSWORD=postgres -e
   POSTGRES_USER=postgres -e POSTGRES_DB=smsone -p 35433:5432 --shm-size=1g postgres:18.4-alpine`.
   Port 35433 rather than 5432 because this box already has a native Postgres and an ssh tunnel
   there — the same reason `docker/.env` shifts everything into the 2xxxx range. **Leave the server
   settings alone**: `max_connections = 100` and `max_locks_per_transaction = 64` are the pair the
   ADR's lock arithmetic is stated against, and changing either invalidates the comparison.
2. **Compile.** `javac` against the resolved Gradle artifacts plus the two build directories. The
   artifacts are `flyway-core` 12.4.0 and `flyway-database-postgresql`, `postgresql` 42.7.11,
   `spring-core` / `spring-jdbc` / `spring-beans`, `HikariCP`, and `jackson` 3 — flyway-core 12.4.0
   loads `tools.jackson.databind`, which in turn needs `jackson-annotations` 2. All of them are
   already in `~/.gradle/caches/modules-2/files-2.1`.
3. **Run, in order:** `SiloCeilingBench migrate 200`, then `seed`, then `bench 1,10,50,100,200`,
   then `SiloCeilingControls 50,100,200`, then `LockCapacityProbe`, then
   `DropSchemaCeilingProbe`. `SiloCeilingBench footprint` reports relation counts, catalog size and
   JDBC metadata time — run it **before** seeding if you want the empty-schema footprint, since it
   is the "empty relation files" figure that the ADR's §1 rejection of uniform schema-per-tenant
   rests on.

`TENANCY_BENCH_URL`, `TENANCY_BENCH_USER` and `TENANCY_BENCH_PASS` override the connection.

## Two traps worth knowing before you trust a number

**Cold versus warm is the whole story.** Almost every figure here is 10× larger on the first
execution against a backend that has never touched those relations than it is in steady state —
UNION planning, `pg_dump -n`, JDBC metadata, all of them. A benchmark that reports run 1 reports the
cold number. `SiloCeilingControls` exists to keep the two apart; report both or report neither.

**Assert row counts between sections, not only before them.** The first run of this benchmark was
thrown away because a bulk text substitution had injected a `truncate` into the per-home loop, which
emptied every home partway through the run — after the planning and lock sections and before the
controls. The tell was `pg_stat_user_tables.n_live_tup = 0` alongside `n_tup_del = 0`: a truncate,
not a delete.
