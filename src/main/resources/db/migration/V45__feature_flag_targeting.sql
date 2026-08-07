-- Flag targeting: a percentage rollout on the flag itself (bucketed deterministically by org, only
-- meaningful while the flag is enabled) and hard per-org overrides that beat everything.
--
-- THE BUCKETING INPUT IS organization.id, and pinning that in writing is the whole point of this
-- paragraph. The bucket is a hash of (flag_key, org id), which is what makes "10% of orgs" mean the
-- SAME 10% on every request and every instance. That stickiness is a property of the VALUE, not of
-- the column — so when the tenant key moved from the Keycloak organization id to organization.id
-- (V11), every org re-bucketed and any half-finished rollout flipped for a fraction of tenants.
-- Pre-production that costs nothing, which is exactly why it is recorded rather than left implicit:
-- the input must never change again as a SIDE EFFECT of something else. A rollout that has to survive
-- a future re-key needs its own bucket_key column, not whichever identifier happens to be the tenant
-- key that week.
alter table feature_flag add column percentage int;

-- Overrides stay FLAT on purpose — no version, no deleted_at, no audit columns. An override is a
-- switch position, not an aggregate: clearing it is a real delete and there is no history to keep.
-- Do not normalise this into the BaseEntity shape while retargeting org_id; the flatness is why the
-- retarget here needs no partial-index dance, unlike every soft-deletable neighbour.
create table feature_flag_org_override (
    id         uuid        primary key,
    flag_key   varchar(150) not null,      -- soft ref to feature_flag.flag_key, and NOT a foreign key
                                           -- even though both tables are the settings module: the
                                           -- parent's uniqueness is a PARTIAL index over live rows
                                           -- (V17) and Postgres will not point an FK at one
    org_id     uuid        not null,       -- organization.id; soft ref, no cross-module FK
    enabled    boolean     not null,
    created_at timestamptz not null,
    unique (flag_key, org_id)              -- total, not partial: nothing here is soft-deletable, so a
                                           -- collision during a retarget fails hard and loudly
);
