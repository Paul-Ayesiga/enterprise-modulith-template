-- Soft delete for every aggregate root: deletion is recorded, not executed.
--
-- Applies to the seven AggregateRoot tables. Deliberately NOT applied to:
--   audit_log            - append-only by definition; a deletable audit trail is not an audit trail
--   in_app_notification  - BaseEntity, not an aggregate root; genuinely disposable
--   webhook_delivery     - a log, not an aggregate; trimmed by retention, not by users
--   role_permission      - an @ElementCollection of org_role, whose lifecycle it follows
--   framework tables     - event_publication, shedlock, idempotency_key, flyway_schema_history
--
-- THE UNIQUE-KEY PROBLEM: a soft-deleted row still occupies its unique key. Left alone, deleting the
-- role 'AUDITOR' would permanently forbid ever creating another 'AUDITOR' in that org, and the failure
-- would look like a mysterious 409 against a row nobody can see. So every unique constraint on these
-- tables is replaced by a PARTIAL unique index restricted to live rows.
--
-- The drops are unqualified (no `if exists`) ON PURPOSE: these constraints were created by this
-- project's own migrations, so their names are known. If a name has drifted, this must fail loudly at
-- deploy time rather than silently leave the old total-unique constraint in place — which would look
-- like it worked until the first re-create of a deleted key.

-- ---------------------------------------------------------------------------------------------
-- 1. The soft-delete column
-- ---------------------------------------------------------------------------------------------
alter table setting              add column deleted_at timestamptz;
alter table feature_flag         add column deleted_at timestamptz;
alter table app_user             add column deleted_at timestamptz;
alter table organization         add column deleted_at timestamptz;
alter table org_role             add column deleted_at timestamptz;
alter table membership           add column deleted_at timestamptz;
alter table webhook_subscription add column deleted_at timestamptz;

-- ---------------------------------------------------------------------------------------------
-- 2. Unique constraints -> partial unique indexes over live rows only
-- ---------------------------------------------------------------------------------------------

-- setting.setting_key (inline UNIQUE -> Postgres default name <table>_<column>_key)
alter table setting drop constraint setting_setting_key_key;
create unique index uq_setting_key_live on setting (setting_key) where deleted_at is null;

-- feature_flag.flag_key
alter table feature_flag drop constraint feature_flag_flag_key_key;
create unique index uq_feature_flag_key_live on feature_flag (flag_key) where deleted_at is null;

-- app_user.subject
alter table app_user drop constraint app_user_subject_key;
create unique index uq_app_user_subject_live on app_user (subject) where deleted_at is null;

-- organization.kc_org_id and organization.alias
alter table organization drop constraint organization_kc_org_id_key;
alter table organization drop constraint organization_alias_key;
create unique index uq_organization_kc_org_id_live on organization (kc_org_id) where deleted_at is null;
create unique index uq_organization_alias_live     on organization (alias)     where deleted_at is null;

-- org_role (org_id, code) - the case that motivates all of this
alter table org_role drop constraint uq_org_role_org_code;
create unique index uq_org_role_org_code_live on org_role (org_id, code) where deleted_at is null;

-- membership (org_id, user_subject): re-inviting someone previously removed must be possible
alter table membership drop constraint uq_membership_org_user;
create unique index uq_membership_org_user_live on membership (org_id, user_subject) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------
-- 3. Purge support
-- ---------------------------------------------------------------------------------------------
-- The retention job scans for rows deleted before a cutoff. Partial indexes on the deleted rows only:
-- live rows are the overwhelming majority and never match, so indexing them would be dead weight on
-- every write.
create index idx_setting_deleted              on setting              (deleted_at) where deleted_at is not null;
create index idx_feature_flag_deleted         on feature_flag         (deleted_at) where deleted_at is not null;
create index idx_app_user_deleted             on app_user             (deleted_at) where deleted_at is not null;
create index idx_organization_deleted         on organization         (deleted_at) where deleted_at is not null;
create index idx_org_role_deleted             on org_role             (deleted_at) where deleted_at is not null;
create index idx_membership_deleted           on membership           (deleted_at) where deleted_at is not null;
create index idx_webhook_subscription_deleted on webhook_subscription (deleted_at) where deleted_at is not null;
