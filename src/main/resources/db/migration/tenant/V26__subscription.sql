-- The commercial axis of a tenant: which PLAN an org is on. The plan catalogue itself is the
-- platform's (seeded reference data, snapshot-copied into an extracted deployment and then allowed
-- to diverge); this row is the org's own commercial state, so it soft-deletes like every aggregate.
--
-- plan_id IS A SOFT REF, and it is the clearest statement of why the tier axis is not the module
-- axis: `plan` and `org_subscription` sit in the same module, are written by the same service, and
-- the foreign key between them was perfectly sound — V53 cut it anyway, because `plan` is platform
-- and this row is the tenant's. The split moves that cut to the point of creation: `plan` is not on
-- this sequence's search_path and after Phase 7 is not in this database. `SubscriptionService`
-- resolves the plan through the repository before it writes, and AGENTS §5.2 names the destiny — a
-- `PlanCatalog` port, which is what an extracted deployment reads instead of a joined table.
--
-- SPLIT (ADR 0010 §4.1): this is the tenant half of V26. Its sibling is db/migration/platform/V26__subscription.sql.

create table org_subscription
(
    id                 uuid        not null primary key,
    org_id             uuid        not null,             -- organization.id — soft ref (no FK, module boundary)
    plan_id            uuid        not null,             -- plan.id — SOFT ref, no FK: platform tier
    status             varchar(20) not null,             -- ACTIVE | TRIALING | PAST_DUE | CANCELLED
    current_period_end timestamptz,                      -- null = evergreen (no billing integration yet)
    version            bigint      not null,
    created_at         timestamptz not null,
    created_by         uuid        ,
    updated_at         timestamptz,
    updated_by         uuid        ,
    deleted_at         timestamptz
);

-- One live subscription per org; a soft-deleted row frees the slot (V17's partial-unique rule).
create unique index uq_org_subscription_org_live on org_subscription (org_id)
    where deleted_at is null;

-- Retention purge scan (V17 pattern).
create index idx_org_subscription_deleted on org_subscription (deleted_at)
    where deleted_at is not null;
