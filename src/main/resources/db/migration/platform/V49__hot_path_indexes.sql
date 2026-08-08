-- Indexes for queries the code already runs but nothing served, plus two that served nothing
-- (2026-08-07 post-identity-refactor performance audit). Every claim below is EXPLAIN (ANALYZE,
-- BUFFERS) backed against a seeded throwaway Postgres 18.4 (200k person, 200k membership, 200k
-- ticket, 200k webhook_delivery, 1M api_usage_daily, 100k org_group, 5k organization); the measured
-- before/after is quoted next to each one.
--
-- Every index names the query it serves (AGENTS §4.5). Partials exclude soft-deleted rows because
-- the queries do — with two deliberate exceptions marked TOTAL below, where a PARTIAL index is
-- exactly what the database cannot use.
--
-- None of these is a rename error. The refactor's own hot path (external_identity → person →
-- organization → membership) was already indexed correctly and is untouched here; what the rename
-- walked past is keyset listings that never had a supporting index in the first place.
--
-- SPLIT (ADR 0010 §4.1): this is the platform half of V49. Its sibling is db/migration/tenant/V49__hot_path_indexes.sql.

-- ---------------------------------------------------------------------------------------------
-- 1. Keyset listings with no index at all: full scan + top-N heapsort on EVERY page, cursor depth
--    irrelevant. All four sort (created_at desc, id desc), which is the house keyset (ADR 0002).
-- ---------------------------------------------------------------------------------------------

-- PersonAccessService.list — the platform person listing. 2516 buffers / 18.3 ms -> 4 / 0.130 ms.
create index idx_person_recent on person (created_at desc, id desc)
    where deleted_at is null;

-- OrganizationService.platformList, and — the reason this one is not merely a listing cost —
-- SystemRoleCatalogReconciler, which walks the ENTIRE tenant table in 500-row keyset pages on every
-- instance start. One DESC index serves both directions: the reconciler's ascending scan plans as
-- Index Scan Backward, so no second index is needed.
create index idx_organization_recent on organization (created_at desc, id desc)
    where deleted_at is null;

-- SettingService.list / FeatureFlagService.list. Admin-scale tables, so this is uniformity rather
-- than urgency — but an unindexed keyset is an unindexed keyset, and these are the two that were
-- left behind when V20 indexed the others.
create index idx_setting_recent on setting (created_at desc, id desc)
    where deleted_at is null;

create index idx_feature_flag_recent on feature_flag (created_at desc, id desc)
    where deleted_at is null;

-- consent_record — the same mis-ordering as idx_ticket_status, one table over. idx_consent_person was
-- (person_id, purpose, created_at desc) while its only reader is findByPersonIdOrderByCreatedAtDesc:
-- `purpose` between the filter and the sort column forced an in-memory sort (confirmed: a quicksort
-- Sort node). Nothing anywhere queries consent by purpose. Harmless today only because the row count
-- per person is tiny and the query is unpaginated — fixed now so the pattern does not survive as a
-- template for the next table that copies it.
drop index idx_consent_person;

create index idx_consent_person on consent_record (person_id, created_at desc);

-- ---------------------------------------------------------------------------------------------
-- 4. Job scans over growing ledgers.
-- ---------------------------------------------------------------------------------------------

-- UsageExportJob: `where exported = false and day < current_date order by day`. The only index was
-- the (org_id, day) primary key, so the nightly Kill Bill bridge scanned the whole ledger — which
-- grows orgs x days (5000 orgs x 365d = 1.8M rows/yr) — to find one day's unexported rows.
-- Partial on the flag: exported rows are the overwhelming majority and this query never wants one.
-- 8410 buffers / 31.8 ms -> 7 buffers / 0.085 ms at 1M rows.
create index idx_api_usage_unexported on api_usage_daily (day)
    where exported = false;

-- ---------------------------------------------------------------------------------------------
-- 5. audit_log — the filter and the sort now agree on a column, so an index can serve both.
-- ---------------------------------------------------------------------------------------------

-- AuditQueryService ranges on occurred_at and used to keyset on created_at, which no single index
-- can satisfy: idx_audit_log_occurred (V20) served the range but not the order, idx_audit_org_created
-- (V13) served the order but not the range, so a windowed query walked the whole org history newest-
-- first and discarded non-matching rows from the heap. The service now keysets on occurred_at; these
-- are the indexes that shape. The org-scoped endpoint always supplies an org, the platform-scoped one
-- does not, hence both.
create index idx_audit_org_occurred on audit_log (org_id, occurred_at desc, id desc);

create index idx_audit_occurred_recent on audit_log (occurred_at desc, id desc);

-- Strictly subsumed by the line above — same leading column, neither is partial, so the new one is a
-- prefix superset and a btree scans it in either direction. This claim is STRUCTURAL rather than
-- measured, which is why it is the only drop here that was not proven by re-planning its reader.
-- audit_log takes a write on every mutation in the system, so a spare index on it is not free.
drop index idx_audit_log_occurred;

-- NOT dropped, and worth writing down rather than leaving as a silent leftover: idx_audit_created
-- (created_at desc, id desc) and idx_audit_org_created (org_id, created_at desc, id desc) from V13
-- no longer have a reader now that the query sorts on occurred_at — OrgExportService's org_id probe
-- is served by idx_audit_org_occurred above. They stay because "no reader today" is a weaker claim
-- than the two proven drops below, and because created_at ordering is the obvious thing an ops query
-- reaches for. Revisit with an EXPLAIN in hand, not from this comment.

-- ---------------------------------------------------------------------------------------------
-- 6. Two indexes that serve no query and cost every write. Both are PROVEN redundant, not guessed:
--    dropping each one and re-running its reader produced an identical plan shape and buffer count
--    through the surviving index.
-- ---------------------------------------------------------------------------------------------

-- Strictly subsumed by uq_external_identity_person_issuer_live (person_id, provider, issuer) where
-- deleted_at is null — same leading column, identical predicate. findByPersonIdOrderByLinkedAtAsc
-- switches to the unique index with no change in cost. This is pure write amplification on the
-- hottest table the identity refactor created, which is the one place it is least affordable.
drop index idx_external_identity_person;
