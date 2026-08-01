-- Indexes for queries the code already runs but nothing served (2026-08-01 audit, M23 + LOW items).
-- Every index names the query it serves (AGENTS §4.5); partials exclude soft-deleted rows because
-- the queries do. One drop: idx_app_user_email served NO query — the only email lookup is
-- case-insensitive (upper(email) = upper(?)), which a plain btree on email cannot answer.

-- MemberService.list keyset pages: where org_id = ? order by created_at desc, id desc limit n.
create index idx_membership_org_recent
    on membership (org_id, created_at desc, id desc) where deleted_at is null;

-- Role listing per org, same keyset shape (RoleService.list / RoleController).
create index idx_org_role_org_recent
    on org_role (org_id, created_at desc, id desc) where deleted_at is null;

-- Webhook subscription listing per org, same keyset shape (WebhookSubscriptionService.list).
create index idx_webhook_subscription_org_recent
    on webhook_subscription (org_id, created_at desc, id desc) where deleted_at is null;

-- UserDirectory.findSubjectByEmail — findFirstByEmailIgnoreCase… compiles to upper(email) = upper(?).
-- Unqualified drop on purpose: a drifted name must fail loudly here, not linger (AGENTS §4.5).
drop index idx_app_user_email;
create index idx_app_user_email_ci on app_user (upper(email)) where deleted_at is null;

-- The from/to window filter on both audit endpoints (AuditController occurred-at range probe).
create index idx_audit_log_occurred on audit_log (occurred_at);

-- Retention purge scans (WebhookRetentionJob / NotificationRetentionJob): terminal rows by age.
-- Partial, because live PENDING/PROCESSING rows are the claim index's business, not retention's.
create index idx_webhook_delivery_terminal
    on webhook_delivery (created_at) where status in ('DELIVERED', 'FAILED');
create index idx_notification_delivery_terminal
    on notification_delivery (created_at) where status in ('SENT', 'FAILED');
