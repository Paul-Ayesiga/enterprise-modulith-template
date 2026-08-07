-- Indexes for queries the code already runs but nothing served (2026-08-01 audit, M23 + LOW items).
-- Every index names the query it serves (AGENTS §4.5); partials exclude soft-deleted rows because
-- the queries do. The org_id all three of these index is organization.id (V11), which is the same
-- value the callers now filter on — an index and its query must agree about what the key means.

-- MemberService.list keyset pages: where org_id = ? order by created_at desc, id desc limit n.
create index idx_membership_org_recent
    on membership (org_id, created_at desc, id desc) where deleted_at is null;

-- Role listing per org, same keyset shape (RoleService.list / RoleController).
create index idx_org_role_org_recent
    on org_role (org_id, created_at desc, id desc) where deleted_at is null;

-- Webhook subscription listing per org, same keyset shape (WebhookSubscriptionService.list).
create index idx_webhook_subscription_org_recent
    on webhook_subscription (org_id, created_at desc, id desc) where deleted_at is null;

-- THE EMAIL LOOKUP USED TO BE FIXED HERE and no longer is, which is worth a sentence rather than a
-- silent absence: this migration dropped a plain btree on app_user.email and re-created it folded,
-- because the only email query is case-insensitive and a plain btree cannot answer it. Neither index
-- exists now. app_user is absorbed into person (V10), the address lives in person_contact, and that
-- table declares its own folded index (idx_person_contact_value, V28) at birth — next to the rule it
-- serves, which is where an index belongs. The fold moved with it, upper() -> lower(), so the
-- database and the query agree on one form instead of the query merely tolerating the index.

-- The from/to window filter on both audit endpoints (AuditController occurred-at range probe).
create index idx_audit_log_occurred on audit_log (occurred_at);

-- Retention purge scans (WebhookRetentionJob / NotificationRetentionJob): terminal rows by age.
-- Partial, because live PENDING/PROCESSING rows are the claim index's business, not retention's.
create index idx_webhook_delivery_terminal
    on webhook_delivery (created_at) where status in ('DELIVERED', 'FAILED');
create index idx_notification_delivery_terminal
    on notification_delivery (created_at) where status in ('SENT', 'FAILED');
