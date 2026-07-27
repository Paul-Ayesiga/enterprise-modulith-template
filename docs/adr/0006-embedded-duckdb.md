# ADR 0006 — Embedded DuckDB for analytics, UTC marts, exact decimals

- **Status:** Accepted · **Date:** 2026-07-27

## Decision
OLAP runs in-process on DuckDB behind the `AnalyticsEngine` seam. Marts are materialized from
Postgres by cursor-streamed extraction (autocommit off) into typed DuckDB tables — `DECIMAL(p,s)`
stays decimal, timestamptz becomes `TIMESTAMPTZ` — swapped in atomically via a staging table.
Every connection pins `SET threads`, `SET memory_limit`, and `SET TimeZone='UTC'`; ephemeral
in-memory queries are permit-bounded. Parquet snapshots use DuckDB's statically-linked writer.

## Why
No analytics infrastructure to operate; Postgres stays OLTP-only. The specific rules exist
because review agents reproduced the failure modes empirically: DOUBLE-coerced decimals drift
(`SUM(10k × 0.01) = 100.00000000001425`), naive timestamps bucket the same instant into different
days on different hosts, and DuckDB's caps are global per instance — not per connection.

## Consequences
Marts are point-in-time copies (refresh via scheduler when staleness matters). The engine seam
keeps ClickHouse/Trino/Postgres-fallback as pure implementation swaps. Analytics SQL is
developer-authored — never end-user input.
