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
-- SPLIT (ADR 0010 §4.1): this is the tenant half of V49. Its sibling is db/migration/platform/V49__hot_path_indexes.sql.

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

-- Subsumed by idx_webhook_subscription_org_recent (org_id, created_at desc, id desc) where
-- deleted_at is null (V20). Every reader goes through @SQLRestriction("deleted_at is null") so all
-- of them match the partial index, and existsIncludingDeleted keys on the primary key. Unlike
-- idx_org_role_org above, webhook_subscription.org_id carries no FK (cross-module soft ref), so no
-- RI check needs a total index here.
drop index idx_webhook_sub_org;
