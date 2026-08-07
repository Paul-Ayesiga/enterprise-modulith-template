-- Machine credentials. The secret is HASHED (SHA-256), never encrypted — unlike webhook signing
-- secrets we never need the plaintext back, we only verify a presented one, so a DB dump yields
-- nothing usable. org_id null = a PLATFORM key (read-only support tier, minted by platform-admin);
-- org keys carry a permission SUBSET capped at mint time by what the minter holds — a key can
-- never out-rank its creator (the escalation guard's rule, applied to machines).
--
-- org_id is organization.id (V11) and reaches it as a SOFT ref with no FK — apikeys and organization
-- are different modules (AGENTS §1). The column name and its meaning are unchanged; only the value
-- space is, from a Keycloak organization id to a row we own.
--
-- owner_person_id is the column V10 said this table was missing. An external_identity row with
-- provider = API_KEY asserts "this credential IS this person", which is true of a personal access
-- token and false of an org key — so V10's header calls that vocabulary value RESERVED AND UNUSED
-- until api_key can name an owner. This is that half. Null still means the key belongs to an org
-- (org_id set) or to the platform (both null) and resolves to NO person; manufacturing a synthetic
-- one is the harm V10 spells out. Nothing writes the column yet — the mint path and the entity
-- arrive with the personal-token slice — and it is declared here rather than later so the
-- vocabulary has something to point at instead of a promise.

create table api_key
(
    id              uuid         not null primary key,
    org_id          uuid,                              -- organization.id; null = platform key
    owner_person_id uuid,                              -- person.id when this is a PERSONAL access
                                                       -- token; null = an org or platform key, and
                                                       -- neither of those is any person
    name            varchar(100) not null,
    prefix          varchar(20)  not null,             -- public half (sk_xxxxxxxx), always displayable
    secret_hash     varchar(64)  not null,             -- SHA-256 hex of the secret half
    permissions     text,                              -- comma-joined org permission codes (org keys)
    platform_tier   varchar(30),                       -- platform keys: always platform-support today
    expires_at      timestamptz,
    last_used_at    timestamptz,
    version         bigint       not null,
    created_at      timestamptz  not null,
    created_by      uuid        ,
    updated_at      timestamptz,
    updated_by      uuid        ,
    deleted_at      timestamptz
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
