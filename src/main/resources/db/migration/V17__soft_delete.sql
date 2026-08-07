-- Soft delete for every aggregate root: deletion is recorded, not executed.
--
-- Nine aggregate tables exist by this point in the chain. Six of them — person and external_identity
-- (V10), organization, external_organization, org_role and membership (V11) — declare `deleted_at`
-- and their partial unique indexes AT BIRTH, because they are authored knowing this rule exists and
-- because the reasoning for each partial index belongs next to the index it explains. This migration
-- retrofits the three that predate the rule: setting (V3), feature_flag (V6), webhook_subscription
-- (V15). Everything created after V17 declares the column inline as well (V28, V29, V30, V31, V32, …).
--
-- The rule itself, and where it does NOT apply, is stated once — here:
--   audit_log             - append-only by definition; a deletable audit trail is not an audit trail
--   impersonation_session - the same rule one step sharper (V18): end-only, never deleted, because a
--                           soft-delete flag would let an oversight tool erase its own oversight
--   in_app_notification   - BaseEntity, not an aggregate root; genuinely disposable
--   webhook_delivery      - a log, not an aggregate; trimmed by retention, not by users
--   role_permission       - an @ElementCollection of org_role, whose lifecycle it follows
--   person_preference     - the idempotency-key species (V28): no lifecycle beyond its person
--   framework tables      - event_publication, shedlock, idempotency_key, flyway_schema_history
--
-- THE UNIQUE-KEY PROBLEM (the reason every unique key on a soft-deletable table is a PARTIAL index
-- rather than a constraint): a soft-deleted row still occupies its unique key. Left alone, deleting
-- the role 'AUDITOR' would permanently forbid ever creating another 'AUDITOR' in that org, and the
-- failure would look like a mysterious 409 against a row nobody can see. So a unique key on any of
-- these tables is `create unique index … where deleted_at is null`, never `constraint … unique`.
--
-- The drops below are unqualified (no `if exists`) ON PURPOSE: these constraints were created by this
-- project's own migrations, so their names are known. If a name has drifted, this must fail loudly at
-- deploy time rather than silently leave the old total-unique constraint in place — which would look
-- like it worked until the first re-create of a deleted key.

-- ---------------------------------------------------------------------------------------------
-- 1. The soft-delete column
-- ---------------------------------------------------------------------------------------------
alter table setting              add column deleted_at timestamptz;
alter table feature_flag         add column deleted_at timestamptz;
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

-- webhook_subscription has no unique key to convert: a tenant may point two subscriptions at the
-- same URL, and nothing else about the row is unique.

-- ---------------------------------------------------------------------------------------------
-- 3. Purge support
-- ---------------------------------------------------------------------------------------------
-- The retention job scans for rows deleted before a cutoff. Partial indexes on the deleted rows only:
-- live rows are the overwhelming majority and never match, so indexing them would be dead weight on
-- every write. The six tables that declare deleted_at at birth carry their own copy of this index.
create index idx_setting_deleted              on setting              (deleted_at) where deleted_at is not null;
create index idx_feature_flag_deleted         on feature_flag         (deleted_at) where deleted_at is not null;
create index idx_webhook_subscription_deleted on webhook_subscription (deleted_at) where deleted_at is not null;
