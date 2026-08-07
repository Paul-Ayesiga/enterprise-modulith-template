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

-- ---------------------------------------------------------------------------------------------
-- 2. ticket — a MIS-ORDERED index, which is worse than a missing one because it looks present.
-- ---------------------------------------------------------------------------------------------

-- idx_ticket_status was (status, priority, created_at desc): `priority` sits BETWEEN the filter
-- column and the sort column, so an index scan on status= yields rows in (priority, created_at)
-- order rather than (created_at), and `id` was absent entirely. The planner therefore ignored it and
-- seq-scanned 200k tickets into a top-N heapsort for a 20-row page (3797 buffers / 43.2 ms).
-- Nothing filters or sorts on priority — SlaPolicyRepository and OrgSlaOverrideRepository key on it,
-- neither goes through ticket — so it comes out of the middle rather than being worked around.
drop index idx_ticket_status;
create index idx_ticket_status_recent on ticket (status, created_at desc, id desc)
    where deleted_at is null;                                    -- 5 buffers / 1.1 ms

-- TicketRepository.pageForQueue with status == null: the tenant-wide support queue, which no
-- org-prefixed index can serve (idx_ticket_org leads with org_id). 3862 buffers / 80.8 ms per page,
-- at every cursor depth.
create index idx_ticket_recent on ticket (created_at desc, id desc)
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
-- 3. TOTAL indexes for FK referential-integrity checks. THE `where deleted_at is null` PREDICATE IS
--    DELIBERATELY ABSENT and must stay absent: Postgres cannot use a PARTIAL index to satisfy an RI
--    check, so both of these tables' existing partial org_id indexes are invisible to it and each
--    purged organization row seq-scans the whole child table.
-- ---------------------------------------------------------------------------------------------

-- Measured on one childless org delete: membership_org_id_fkey 42.2 ms and org_group_org_id_fkey
-- 12.6 ms, against org_role_org_id_fkey's 1.05 ms — org_role is the control, and the only one of the
-- three that already has a total (org_id) index (V11). Adding the membership index alone dropped its
-- trigger to 0.726 ms while org_group stayed at 12.6, which confirms the two gaps independently.
-- Cost scales as (orgs purged x rows in child table) on every nightly SoftDeletePurgeJob run.
create index idx_membership_org on membership (org_id);
create index idx_org_group_org_fk on org_group (org_id);

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

-- OrgExportService: `select * from webhook_delivery where org_id = ? limit 1000` — the GDPR /
-- offboarding export. webhook_delivery carries org_id (V15, denormalised from the parent) but
-- indexed only next_attempt_at, locked_at, subscription_id and created_at, so the export seq-scanned
-- the largest table in the schema. LIMIT 1000 does not rescue an org with few deliveries: it has to
-- scan to the end to prove there are no more. 3281 buffers / 27.8 ms to return 40 rows.
create index idx_webhook_delivery_org on webhook_delivery (org_id, created_at desc);

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

-- Subsumed by idx_webhook_subscription_org_recent (org_id, created_at desc, id desc) where
-- deleted_at is null (V20). Every reader goes through @SQLRestriction("deleted_at is null") so all
-- of them match the partial index, and existsIncludingDeleted keys on the primary key. Unlike
-- idx_org_role_org above, webhook_subscription.org_id carries no FK (cross-module soft ref), so no
-- RI check needs a total index here.
drop index idx_webhook_sub_org;
