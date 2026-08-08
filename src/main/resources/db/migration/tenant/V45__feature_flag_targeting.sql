-- Flag targeting: a percentage rollout on the flag itself (bucketed deterministically by org, only
-- meaningful while the flag is enabled) and hard per-org overrides that beat everything.
--
-- THE OVERRIDE IS THE TENANT'S, THE PERCENTAGE IS THE PLATFORM'S, which is why this file has two
-- halves. `feature_flag.percentage` is a property of the flag — one catalogue, one rollout curve —
-- and lives in the sibling with the paragraph pinning organization.id as the bucketing input. An
-- override is one org's decision to opt out of that curve, so it travels with the org. Reading order
-- is unchanged (`FeatureFlagService.isEnabledFor` checks the override first and beats everything),
-- but after Phase 2 the two reads are in two schemas, and after Phase 7 possibly two databases.
--
-- SPLIT (ADR 0010 §4.1): this is the tenant half of V45. Its sibling is db/migration/platform/V45__feature_flag_targeting.sql.

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
