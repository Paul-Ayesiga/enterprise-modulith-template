# Porting the template to another RDBMS

This template's system-of-record is **PostgreSQL, by design** — it leans on Postgres for real
capability (soft-delete partial indexes, lock-free queue fan-out, native full-text search), not by
accident. That is the right default: for a SaaS *you* run the database. This document exists for the
other case — building a product **from this template** that must run on **Oracle, SQL Server, or
MySQL** (an enterprise/on-prem mandate).

The good news: the coupling is **concentrated, not smeared**. The architecture — modules, auth/RBAC,
the gateway, the event/outbox plane, the HTTP contract, billing, notifications — is 100%
database-agnostic. The Postgres-specific code lives in **five seams**, and porting is touching those,
not a rewrite. Find them all at once:

```bash
grep -rn "PORTING:" src/main/java                              # the marked seams in code
grep -rniE "skip locked|on conflict|to_tsvector|websearch_to_tsquery|word_similarity" src/main/java
grep -rniE "timestamptz|on conflict|generated always|where .* is (not )?null" src/main/resources/db/migration
```

## The five seams

### 1. The `DbDialect` seam — `SKIP LOCKED` queue claims (already isolated)

The durable queues (`NotificationDeliveryQueue`, `WebhookDeliveryQueue`, `ExchangeJobStore`) claim a
batch of rows with `... for update skip locked` — the competing-consumer primitive that lets many
workers/instances drain a queue without double-claiming. That **clause** is already behind a seam:
`ug.co.smsone.shared.persistence.DbDialect#skipLocked()`, defaulting to `PostgresDialect`.

- **Postgres / Oracle / MySQL 8+** all spell it `for update skip locked` — provide a `DbDialect` bean
  returning that string and the clause is done.
- **SQL Server** has no such clause; it uses a `WITH (UPDLOCK, READPAST)` table hint in the `FROM`,
  which changes the statement shape — so on SQL Server the whole claim query is rewritten (see #2).

To swap the dialect, a product just registers its own bean — `@ConditionalOnMissingBean` makes it win:

```java
@Bean DbDialect dbDialect() { return () -> "for update skip locked"; } // or the vendor's form
```

### 2. The queue-claim *statements* are whole-statement Postgres constructs

Be honest about this: the claim is not just the clause. It is
`UPDATE t SET … FROM (SELECT id … LIMIT ? <skip-locked>) c WHERE t.id = c.id RETURNING …`, using
`UPDATE … FROM`, `RETURNING`, `now()`, and `interval '1 millisecond'` — all Postgres-shaped. On a
database that isn't Postgres-compatible you rewrite the three claim methods per vendor:

| Postgres | Oracle | SQL Server | MySQL 8 |
|---|---|---|---|
| `UPDATE … FROM (subselect … FOR UPDATE SKIP LOCKED) … RETURNING` | `SELECT … FOR UPDATE SKIP LOCKED` into a cursor, then `UPDATE … RETURNING INTO` (or a `MERGE`) | `UPDATE TOP(n) … WITH (UPDLOCK, READPAST) … OUTPUT inserted.*` | `SELECT id … FOR UPDATE SKIP LOCKED` then a second `UPDATE … WHERE id IN (…)` (no `RETURNING`) |
| `now()` | `SYSTIMESTAMP` | `SYSUTCDATETIME()` | `UTC_TIMESTAMP()` |
| `now() - (? * interval '1 millisecond')` | `SYSTIMESTAMP - NUMTODSINTERVAL(?/1000,'SECOND')` | `DATEADD(ms, -?, SYSUTCDATETIME())` | pass the cutoff as a computed parameter |

Cleanest cross-vendor move: **compute the stale-lock cutoff in Java** and pass it as a bound
parameter (the idempotency store already does this), removing the `interval` arithmetic from SQL.

### 3. `ON CONFLICT` upserts

Upserts use Postgres `INSERT … ON CONFLICT (…) DO UPDATE|DO NOTHING`:
`IdempotencyStore` (conditional DO UPDATE), `GatewayUsageReportController` (additive DO UPDATE),
`SearchIndexStore`, and `DO NOTHING` in `EventInbox` / `ExchangeJobStore`.

| Postgres | Oracle | SQL Server | MySQL |
|---|---|---|---|
| `INSERT … ON CONFLICT (k) DO UPDATE SET c = excluded.c` | `MERGE INTO t USING dual ON (k) WHEN MATCHED THEN UPDATE … WHEN NOT MATCHED THEN INSERT …` | `MERGE t AS tgt USING (…) AS src ON (k) WHEN MATCHED … WHEN NOT MATCHED …` | `INSERT … ON DUPLICATE KEY UPDATE c = VALUES(c)` |
| `… ON CONFLICT DO NOTHING` | `MERGE … WHEN NOT MATCHED THEN INSERT` | `IF NOT EXISTS(…) INSERT` or `MERGE` | `INSERT IGNORE …` |

The `additive` usage upsert (`requests = api_usage_daily.requests + excluded.requests`) is the one to
watch — keep it atomic in the target's `MERGE`/`ON DUPLICATE KEY`, don't split it into read-modify-write.

### 4. Native full-text search

`SearchQueryService` / `SearchIndexStore` use `websearch_to_tsquery` + `ts_rank_cd` over a `tsvector`
column, with a `pg_trgm` `word_similarity` fallback for typos/prefixes. **There is no portable SQL
equivalent** — every RDBMS has a different FTS engine, and none matches Postgres's ranking + trigram
combination.

**Recommendation: don't reimplement per-vendor. Swap the search adapter for an external engine.** The
`search` module is already behind the `SearchIndex` / `Search` ports, so a product replaces the
Postgres-FTS implementation with an **OpenSearch/Elasticsearch** adapter (index on the same
`SearchIndexed` events the module already consumes). This is the same move you'd make at scale on
Postgres anyway, and it removes the deepest lock-in cleanly.

### 5. Schema seams in the migrations

`ddl-auto: validate` — the schema **is** the 46 Flyway `.sql` files, so they are the port surface.
Keep Postgres as the canonical set; add per-vendor migrations under Flyway's vendor locations
(`db/migration/{postgresql,oracle,sqlserver,mysql}`) rather than editing in place. The mechanical
translations:

| Postgres | Oracle | SQL Server | MySQL |
|---|---|---|---|
| `timestamptz` | `TIMESTAMP WITH TIME ZONE` | `datetimeoffset` | `TIMESTAMP` (UTC convention) |
| `uuid` | `RAW(16)` / `VARCHAR2(36)` | `uniqueidentifier` | `BINARY(16)` / `CHAR(36)` |
| `text` | `CLOB` / `VARCHAR2` | `nvarchar(max)` | `TEXT` / `LONGTEXT` |
| **partial index** `… WHERE deleted_at IS NULL` (61 of them) | function-based index | **filtered index** `WHERE …` | **not supported** — drop it (full index) or redesign |
| `create index … using gin (tsv)` | (search moves to #4) | (see #4) | (see #4) |
| `generated always as (…) stored` | virtual column | computed column | generated column |

**MySQL is the awkward target** — no partial indexes at all. The soft-delete + retention design
relies on `WHERE deleted_at IS NULL` partial indexes to keep live-row scans cheap; on MySQL you drop
them for full indexes (heavier) or move soft-deleted rows to an archive table.

## Porting procedure (for a specific product)

1. Add the target JDBC driver and its `flyway-database-*`; set `spring.jpa.database-platform` to the
   Hibernate dialect and change the datasource URL.
2. Provide a `DbDialect` bean for the target (seam #1).
3. Rewrite the three queue-claim statements for the target's `UPDATE`/`RETURNING`/skip-lock shape (#2)
   and the upserts (#3) — the code sites are marked `// PORTING:` and greppable.
4. Replace the Postgres-FTS search implementation with an OpenSearch/Elasticsearch adapter (#4).
5. Translate the migrations into a vendor location, handling partial indexes and types (#5).
6. Point the Testcontainers base at the target container image and run the suite — the tests are
   real-database tests (ADR 0003), so they validate the port end to end.

## What you should NOT do

Do **not** rewrite the template to portable lowest-common-denominator SQL to make every product
DB-agnostic. That trades away the partial indexes, `SKIP LOCKED` fan-out, and native search — the
capabilities that make this template better than a generic starter — to optimize for the rare target.
Keep the template Postgres-first and best-in-class; port per-product, per this checklist.
