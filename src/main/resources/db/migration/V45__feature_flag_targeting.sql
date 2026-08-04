-- Flag targeting: a percentage rollout on the flag itself (bucketed deterministically by org, only
-- meaningful while the flag is enabled) and hard per-org overrides that beat everything.
alter table feature_flag add column percentage int;

create table feature_flag_org_override (
    id         uuid        primary key,
    flag_key   varchar(150) not null,
    org_id     uuid        not null,
    enabled    boolean     not null,
    created_at timestamptz not null,
    unique (flag_key, org_id)
);
