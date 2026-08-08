-- Four index corrections on paths V49 did not reach, one of which OVERTURNS a decision V49 made
-- deliberately (§4). Same method as V49: every number below is EXPLAIN (ANALYZE, BUFFERS) against a
-- seeded throwaway Postgres 18.4 (5.6M rows — 1M api_usage_daily, 600k ticket_message, 510k
-- audit_log, 500k webhook_delivery, 300k notification_delivery, 200k each of person /
-- external_identity / person_contact / membership / ticket, 50k exchange_job, 5k organization),
-- warm cache, repeated until stable, and quoted next to the statement it justifies.
--
-- Where V49 found keyset listings with no index at all, every case below already HAS an index on the
-- right column and is worse off than that sounds. In §1 and §2 the planner picks the index, scans it
-- happily, and still cannot avoid sorting the entire non-terminal backlog. In §3 the index exists and
-- the one caller that needs it — a foreign key — is structurally unable to see it. In §4 two indexes
-- are maintained on every write for a reader that no longer exists. None of the four is a missing
-- index; all four are an index that does not do the job its name implies.
--
-- PLAIN `create index` / `drop index`, NOT `concurrently` — deliberate, and the reasoning does not
-- survive a naive reading of AGENTS §4.6. Flyway wraps each migration in a transaction by default
-- and CONCURRENTLY cannot run inside one, so an index-only migration would normally opt out; §4.6
-- says to do that with a `-- flyway:executeInTransaction=false` line in the file header. THAT LINE
-- DOES NOTHING ON THE FLYWAY THIS PROJECT RUNS. flyway-core 12.4.0 reads script metadata only from a
-- sidecar resource — SqlScriptMetadata.getMetadataResource appends ".conf" to the migration's path
-- and fromResource returns empty metadata when that file is absent; nothing in the parser looks for
-- a `flyway:` directive inside the SQL. The line would be an ordinary SQL comment, the transaction
-- would stay, and the CONCURRENTLY statements would abort with "cannot run inside a transaction
-- block" — taking every integration test down with them at context startup. Verified against the
-- 12.4.0 sources on this build's classpath, not assumed. §4.6 needs correcting; a migration is not
-- the place to correct it.
--
-- SPLIT (ADR 0010 §4.1): this is the tenant half of V50. Its sibling is db/migration/platform/V50__hot_path_index_corrections.sql.

-- ---------------------------------------------------------------------------------------------
-- 2. exchange_job claim — the identical bug one module over, worth far less, fixed anyway.
-- ---------------------------------------------------------------------------------------------

-- ExchangeJobStore.claimOne spans three statuses rather than two, so (status, created_at) yields
-- three sorted runs and the sort is unavoidable for the same reason:
--     Index Scan (21428 rows) -> Sort (quicksort, 1773kB) -> Limit    3242 buffers, 4.38 ms warm
--     Index Scan (1 row)      -> Limit                                   4 buffers, 0.034 ms warm
-- The honest accounting: exchange_job's non-terminal set is bounded by jobs in flight, not by a
-- fan-out queue that can back up into the tens of thousands, and the poller claims ONE job at a time
-- on a slow cadence. Nobody has this problem. It is fixed because the file was already open and the
-- fix costs nothing — and because leaving one of two identical mistakes standing is how the shape
-- gets copied into the third queue.
drop index idx_exchange_job_claim;

create index idx_exchange_job_claim
    on exchange_job (created_at)
    where status in ('PENDING', 'VALIDATING', 'PROCESSING');

-- ---------------------------------------------------------------------------------------------
-- 4. audit_log — THIS OVERTURNS V49. V49 lines 121-126 considered idx_audit_created and
--    idx_audit_org_created, decided to KEEP them, wrote down why, and asked that any revisit arrive
--    "with an EXPLAIN in hand, not from this comment". This is that EXPLAIN. The decision being
--    reversed was reasoned and recorded, not an oversight left lying around, so it is reversed on
--    evidence rather than tidied away.
-- ---------------------------------------------------------------------------------------------

-- V49 kept them on one argument: "created_at ordering is the obvious thing an ops query reaches
-- for." That reader is hypothetical, and three checks say nothing real depends on them.
--
--   Usage. pg_stat_user_indexes over the same window, same table: idx_audit_created 0 scans,
--   idx_audit_org_created 0 scans, against idx_audit_occurred_recent 24 and idx_audit_org_occurred
--   20. AuditQueryService moved its keyset to occurred_at in V49 and stayed there.
--
--   Cascades. Unlike §3 above, no RI check can be silently depending on these: audit_log has zero
--   foreign keys in either direction (nothing references it, it references nothing), so there is no
--   invisible trigger reader of the kind that makes idx_scan = 0 a lie.
--
--   The one real org_id probe. OrgExportService selects audit_log by org_id for the GDPR bundle;
--   re-planned with these two present, it already chooses idx_audit_org_occurred (Index Scan using
--   idx_audit_org_occurred, Index Cond: org_id = ...), so dropping idx_audit_org_created changes
--   nothing for it.
--
-- What the drop is worth, measured, and NOT what an earlier estimate claimed:
--
--   Storage: 29 MB + 38 MB = 67 MB at 510k rows, against a 65 MB heap. Two indexes nothing reads
--   were costing slightly more than the table itself.
--
--   WAL: audit_log takes a write on every mutation in the system, so its index tuples are on the
--   critical path of every request. 766.6 -> 528.3 WAL bytes per inserted row = 31% of this table's
--   write-ahead volume, over an alternating A/B of 20k single-row inserts per arm, each arm starting
--   from a freshly VACUUM FULLed table so page-split behaviour matches. full_page_writes was turned
--   off FOR THE MEASUREMENT ONLY: an FPI is 8 kB against a ~200-byte audit row, so with it on the
--   result is a coin flip on whether a checkpoint lands mid-run (observed: 772 and 1495 bytes/row on
--   two runs of the identical arm). 31% is the intrinsic figure; in production, FPIs dilute the
--   share by an amount that depends on checkpoint frequency.
--
--   Insert time: NO CLAIM. The "~24% faster inserts" figure this drop was originally proposed on came
--   from a bulk INSERT...SELECT, which is not how audit rows are written. Measured the way they
--   actually are — one row per transaction, real full_page_writes — the arms overlap: 86.8 / 113.8 /
--   115.7 us per row with the indexes, 93.6 / 94.4 / 102.5 without. Commit dominates and index
--   maintenance disappears into it. The case for this drop is 67 MB and 31% of the WAL, and stating
--   an insert-time win on top of it would be inventing one.
drop index idx_audit_created;

drop index idx_audit_org_created;
