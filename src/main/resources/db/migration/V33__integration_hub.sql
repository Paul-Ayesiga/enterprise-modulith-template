-- The integration hub: which external provider serves an organization for a given capability
-- (SMS, email, payment gateway), and its config. scope org_id null = the PLATFORM DEFAULT, used
-- when an org has no override of its own. One live enabled integration per (scope, kind) — resolve
-- is deterministic: the org's row if present, else the platform default. Config values live in
-- integration_setting element rows; a value marked secret is AES-GCM encrypted at rest and masked
-- on read (the webhook signing-secret pattern). An integration is a soft-deletable aggregate.

create table integration
(
    id         uuid         not null primary key,
    org_id     uuid,                                  -- null = platform default
    kind       varchar(20)  not null,                 -- SMS_PROVIDER | EMAIL_PROVIDER | PAYMENT_GATEWAY
    provider   varchar(40)  not null,                 -- provider code (e.g. twilio, smtp, stripe)
    enabled    boolean      not null default true,
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100),
    deleted_at timestamptz
);

-- One live integration per (scope, kind). A partial unique index cannot span "org_id IS NULL", so
-- resolution treats a missing org row as "fall through to the platform default"; the app enforces
-- single-per-scope on write.
create unique index uq_integration_org_kind_live on integration (org_id, kind)
    where deleted_at is null and org_id is not null;
create unique index uq_integration_platform_kind_live on integration (kind)
    where deleted_at is null and org_id is null;

create index idx_integration_deleted on integration (deleted_at)
    where deleted_at is not null;

create table integration_setting
(
    integration_id uuid         not null references integration (id) on delete cascade,
    setting_key    varchar(60)  not null,
    setting_value  varchar(600) not null,             -- plaintext, or enc:v1:<...> when secret
    is_secret      boolean      not null default false,
    primary key (integration_id, setting_key)
);
