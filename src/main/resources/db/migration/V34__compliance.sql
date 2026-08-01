-- Compliance: consent, legal holds, erasure requests. None are soft-deletable — like audit_log and
-- impersonation_session, these are the records that must not themselves be quietly removable.
--
-- consent_record is APPEND-ONLY: a withdrawal is a NEW row, never an update, so the full history of
-- what a subject agreed to and when is intact.
create table consent_record
(
    id         uuid         not null primary key,
    subject    varchar(64)  not null,
    purpose    varchar(60)  not null,               -- e.g. marketing, analytics, product-updates
    granted    boolean      not null,               -- true = granted, false = withdrawn
    source     varchar(60),                          -- where the decision came from (ui, import, api)
    created_at timestamptz  not null,
    created_by varchar(100)
);

create index idx_consent_subject on consent_record (subject, purpose, created_at desc);

-- legal_hold: while active (released_at null) the subject's or org's data must NOT be hard-deleted.
-- The purge job and the erasure executor both consult it. Released, never deleted — a released hold
-- is the record that the hold WAS in force.
create table legal_hold
(
    id          uuid         not null primary key,
    scope       varchar(10)  not null,              -- SUBJECT | ORG
    subject     varchar(64),                         -- set when scope = SUBJECT
    org_id      uuid,                                -- set when scope = ORG
    reason      varchar(300) not null,
    placed_by   varchar(100) not null,
    placed_at   timestamptz  not null,
    released_at timestamptz,
    released_by varchar(100)
);

-- The purge/erasure hot check: is there an ACTIVE hold for this subject / this org?
create index idx_legal_hold_active_subject on legal_hold (subject) where released_at is null;
create index idx_legal_hold_active_org on legal_hold (org_id) where released_at is null;

-- erasure_request: a GDPR art. 17 request and its outcome. Kept (a compliance record), not
-- soft-deletable; execution is orchestrated by the compliance module against machinery that
-- already exists (soft delete + the retention purge).
create table erasure_request
(
    id           uuid         not null primary key,
    subject      varchar(64)  not null,
    requested_by varchar(100) not null,
    status       varchar(20)  not null,             -- RECEIVED | EXECUTED | REFUSED
    detail       varchar(300),
    created_at   timestamptz  not null,
    updated_at   timestamptz
);

create index idx_erasure_request_subject on erasure_request (subject, created_at desc);
