-- Indexes for queries the code already runs but nothing served (2026-08-01 audit, M23 + LOW items).
-- Every index names the query it serves (AGENTS §4.5); partials exclude soft-deleted rows because
-- the queries do. The org_id all three of these index is organization.id (V11), which is the same
-- value the callers now filter on — an index and its query must agree about what the key means.
--
-- SPLIT (ADR 0010 §4.1): this is the platform half of V20. Its sibling is db/migration/tenant/V20__audit_fix_indexes.sql.

-- THE EMAIL LOOKUP USED TO BE FIXED HERE and no longer is, which is worth a sentence rather than a
-- silent absence: this migration dropped a plain btree on app_user.email and re-created it folded,
-- because the only email query is case-insensitive and a plain btree cannot answer it. Neither index
-- exists now. app_user is absorbed into person (V10), the address lives in person_contact, and that
-- table declares its own folded index (idx_person_contact_value, V28) at birth — next to the rule it
-- serves, which is where an index belongs. The fold moved with it, upper() -> lower(), so the
-- database and the query agree on one form instead of the query merely tolerating the index.

-- The from/to window filter on both audit endpoints (AuditController occurred-at range probe).
create index idx_audit_log_occurred on audit_log (occurred_at);

create index idx_notification_delivery_terminal
    on notification_delivery (created_at) where status in ('SENT', 'FAILED');
