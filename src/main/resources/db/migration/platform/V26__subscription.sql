-- The commercial axis of a tenant, orthogonal to its lifecycle status: which PLAN an org is on and
-- the entitlements that plan grants. Plans are seeded reference data (the RoleSeeder pattern) and
-- deliberately NOT soft-deletable — a plan is vocabulary, not a user aggregate. The subscription
-- row IS one (org-owned commercial state), so it soft-deletes like every aggregate.
-- Entitlement encoding: a row's PRESENCE enables a feature (stored as limit_value = -1, because
-- Hibernate drops null-valued map entries on load); a POSITIVE limit_value caps a count; an
-- ABSENT row means feature-off for features and UNLIMITED for limits — so ENTERPRISE simply
-- carries no limit rows.
--
-- SPLIT (ADR 0010 §4.1): this is the platform half of V26. Its sibling is db/migration/tenant/V26__subscription.sql.

create table plan
(
    id         uuid primary key,
    code       varchar(30)  not null,
    name       varchar(100) not null,
    rank       int          not null,   -- upgrade ordering, FREE lowest
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        ,
    constraint uq_plan_code unique (code)
);

create table plan_entitlement
(
    plan_id     uuid        not null references plan (id) on delete cascade,
    ent_key     varchar(60) not null,
    limit_value bigint,               -- null = boolean feature; non-null = numeric cap
    primary key (plan_id, ent_key)
);
