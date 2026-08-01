-- Machine credentials. The secret is HASHED (SHA-256), never encrypted — unlike webhook signing
-- secrets we never need the plaintext back, we only verify a presented one, so a DB dump yields
-- nothing usable. org_id null = a PLATFORM key (read-only support tier, minted by platform-admin);
-- org keys carry a permission SUBSET capped at mint time by what the minter holds — a key can
-- never out-rank its creator (the escalation guard's rule, applied to machines).

create table api_key
(
    id            uuid         not null primary key,
    org_id        uuid,                              -- null = platform key
    name          varchar(100) not null,
    prefix        varchar(20)  not null,             -- public half (sk_xxxxxxxx), always displayable
    secret_hash   varchar(64)  not null,             -- SHA-256 hex of the secret half
    permissions   text,                              -- comma-joined org permission codes (org keys)
    platform_tier varchar(30),                       -- platform keys: always platform-support today
    expires_at    timestamptz,
    last_used_at  timestamptz,
    version       bigint       not null,
    created_at    timestamptz  not null,
    created_by    varchar(100),
    updated_at    timestamptz,
    updated_by    varchar(100),
    deleted_at    timestamptz
);

-- Auth lookup: one LIVE key per prefix — a revoked (soft-deleted) key frees nothing by design,
-- the prefix is random enough that reuse never happens in practice, but the constraint keeps the
-- lookup unambiguous.
create unique index uq_api_key_prefix_live on api_key (prefix)
    where deleted_at is null;

create index idx_api_key_org on api_key (org_id, created_at desc, id desc)
    where deleted_at is null;

create index idx_api_key_deleted on api_key (deleted_at)
    where deleted_at is not null;
