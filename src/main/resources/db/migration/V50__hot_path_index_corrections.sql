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
-- Staying inside the transaction is not merely the safe option, it is the better one here. Plain DDL
-- takes a SHARE lock (reads continue, writes block) for the duration of each build, and the whole
-- file applied against the seeded database in 483 ms — 185 ms for the notification_delivery rebuild,
-- 121 ms and 97 ms for the two 200k-row FK indexes, everything else under 35 ms. In exchange the two
-- drop-and-recreate swaps below are ATOMIC, which with CONCURRENTLY they could not be: recreating
-- under the same name would leave a window with no claim index at all, and any poller landing in it
-- seq-scans. Revisit if these tables grow by an order of magnitude — the trade flips on build time,
-- and the sidecar `V<n>__<name>.sql.conf` holding `executeInTransaction=false` is the mechanism that
-- actually works.

-- ---------------------------------------------------------------------------------------------
-- 1. notification_delivery claim — the ORDER BY was the entire cost, and the index's LEADING
--    COLUMN is precisely what stopped the index from paying for it.
-- ---------------------------------------------------------------------------------------------

-- NotificationDeliveryQueue.claim asks for:
--     where (status = 'PENDING'    and next_attempt_at <= now())
--        or (status = 'PROCESSING' and locked_at < now() - staleLock)
--     order by next_attempt_at limit 200
-- The trap: (status, next_attempt_at) reads as "ordered by next_attempt_at", and for a single status
-- it is. This predicate spans TWO. A scan of that index yields the PENDING rows in next_attempt_at
-- order and then the PROCESSING rows in next_attempt_at order — two sorted runs concatenated, which
-- is not a sorted sequence. An index leading with `status` can deliver next_attempt_at order only
-- when `status` is pinned to ONE value, and there is no plan shape that merges the two runs back
-- together (MergeAppend interleaves partitions, not branches of an OR inside one relation). So the
-- planner walked the whole partial index and quicksorted every non-terminal row in the table to hand
-- back 200:
--     Index Scan (23076 rows) -> Sort (quicksort, 1850kB) -> Limit    9674 buffers, 12.1 ms warm
-- Dropping `status` from the KEY — it stays in the predicate, so SENT/FAILED rows are still kept out,
-- which was V9's whole reason for the partial — makes the scan itself the order the query wants. The
-- Sort node disappears, and because nothing has to be materialised any more, LIMIT 200 stops the
-- scan at 200 matches instead of at end-of-index:
--     Index Scan (200 rows) -> Limit                                    253 buffers, 0.12 ms warm
-- 38x fewer buffers today and widening: the old plan was O(backlog), the new one is O(200). Both
-- plans pay the same irreducible 200 heap fetches for the rows actually returned.
--
-- Same name on purpose. V9's comment says this index serves "eligible rows ordered by
-- next_attempt_at"; it was aspirational when written and is true now, so it stays where it is.
drop index idx_notification_delivery_claim;
create index idx_notification_delivery_claim
    on notification_delivery (next_attempt_at)
    where status in ('PENDING', 'PROCESSING');

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
-- 3. TOTAL indexes for FK cascade checks — V49 §3's gap, now for the ON DELETE CASCADE children the
--    identity refactor created. THE `where deleted_at is null` PREDICATE IS DELIBERATELY ABSENT AND
--    MUST STAY ABSENT: Postgres cannot use a PARTIAL index to satisfy a referential-integrity
--    check, because the RI probe is a bare equality on the key with no `deleted_at` qual for the
--    planner to match the predicate against. Every partial index on these columns is therefore
--    invisible to the cascade, and each purged parent seq-scans the whole child table.
-- ---------------------------------------------------------------------------------------------

-- Measured on one 500-row person purge batch (SoftDeletePurgeJob's actual statement), before:
--     Trigger external_identity_person_id_fkey: 3454.3 ms
--     Trigger person_contact_person_id_fkey:    2639.3 ms
--     Execution Time: 6095.8 ms  — 99.9% of a purge batch spent inside two triggers
-- after: 9.7 ms and 9.1 ms, Execution Time 23.7 ms. 257x on the steady-state figure (best observed
-- 11.7 ms, i.e. 521x), and the saving scales as (parents purged x rows in child table) every night
-- at 04:00.
--
-- external_organization is the same shape against the organization purge, and is included on the
-- same reasoning rather than the same magnitude — it holds 5k rows today, so the absolute numbers
-- are small and the ratio is the honest way to read them. The same statement deletes through four
-- FKs at once, three of which (org_role, membership, org_group) already carry total indexes from V11
-- and V49 and therefore act as a built-in control:
--     before   external_organization 11.8 ms   vs controls 1.2-1.4 ms   (~9x the indexed peers)
--     after    external_organization  2.4 ms   vs controls 2.4-2.9 ms   (level with them)
-- Batch total 15.9 -> 10.8 ms. It grows with the tenant count; the ratio is what will not change.
--
-- THESE THREE HAVE NO QUERY READER, AND THAT IS NOT A DEFECT — it is the entire point, and it is
-- also what makes them look deletable. Nothing in the application filters these columns by bare
-- equality: every reader carries `deleted_at is null` and lands on the partial or unique index above
-- it. The only caller is Postgres's own RI trigger. An audit conducted the way V49 was conducted
-- ("name the query this index serves") finds nothing here and concludes dead weight. V49 ran exactly
-- that audit over exactly this column and dropped idx_external_identity_person on it — harmlessly,
-- because that index was itself partial and so was already invisible to the cascade, which is
-- precisely why the gap survived a performance review that went looking at this table.
--
-- pg_stat_user_indexes is not the tiebreaker either, and it fails in the direction that convinces.
-- RI probes DO increment idx_scan (verified: one 500-row person purge moved
-- idx_external_identity_person_fk from 0 to 500) — but the purge runs once a night, so any freshly
-- restored environment, and every environment before its first purge window, shows an entirely
-- credible idx_scan = 0. The `_fk` suffix is the load-bearing part of these three names.
create index idx_external_identity_person_fk on external_identity (person_id);
create index idx_person_contact_person_fk on person_contact (person_id);
create index idx_external_organization_org_fk on external_organization (organization_id);

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
