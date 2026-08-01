-- The commercial axis of a tenant, orthogonal to its lifecycle status: which PLAN an org is on and
-- the entitlements that plan grants. Plans are seeded reference data (the RoleSeeder pattern) and
-- deliberately NOT soft-deletable — a plan is vocabulary, not a user aggregate. The subscription
-- row IS one (org-owned commercial state), so it soft-deletes like every aggregate.
-- Entitlement encoding: a row's PRESENCE enables a feature (stored as limit_value = -1, because
-- Hibernate drops null-valued map entries on load); a POSITIVE limit_value caps a count; an
-- ABSENT row means feature-off for features and UNLIMITED for limits — so ENTERPRISE simply
-- carries no limit rows.

create table plan
(
    id         uuid primary key,
    code       varchar(30)  not null,
    name       varchar(100) not null,
    rank       int          not null,   -- upgrade ordering, FREE lowest
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100),
    constraint uq_plan_code unique (code)
);

create table plan_entitlement
(
    plan_id     uuid        not null references plan (id) on delete cascade,
    ent_key     varchar(60) not null,
    limit_value bigint,               -- null = boolean feature; non-null = numeric cap
    primary key (plan_id, ent_key)
);

create table org_subscription
(
    id                 uuid        not null primary key,
    org_id             uuid        not null,             -- soft ref (no FK — module boundary)
    plan_id            uuid        not null references plan (id),
    status             varchar(20) not null,             -- ACTIVE | TRIALING | PAST_DUE | CANCELLED
    current_period_end timestamptz,                      -- null = evergreen (no billing integration yet)
    version            bigint      not null,
    created_at         timestamptz not null,
    created_by         varchar(100),
    updated_at         timestamptz,
    updated_by         varchar(100),
    deleted_at         timestamptz
);

-- One live subscription per org; a soft-deleted row frees the slot (V17's partial-unique rule).
create unique index uq_org_subscription_org_live on org_subscription (org_id)
    where deleted_at is null;

-- Retention purge scan (V17 pattern).
create index idx_org_subscription_deleted on org_subscription (deleted_at)
    where deleted_at is not null;
