-- Scheduled maintenance windows. ANNOUNCE = banner metadata only (clients show a notice); RESTRICT
-- = during the window, org-scoped WRITES to the covered scope answer 503 + Retry-After (reads pass).
-- Scope platform (org_id null) covers everyone; an org-scoped window covers one tenant. A window is
-- a soft-deletable aggregate — cancelling one keeps the record it existed.

create table maintenance_window
(
    id         uuid         not null primary key,
    org_id     uuid,                                  -- null = platform-wide
    starts_at  timestamptz  not null,
    ends_at    timestamptz  not null,
    mode       varchar(10)  not null,                 -- ANNOUNCE | RESTRICT
    message    varchar(300) not null,
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100),
    deleted_at timestamptz
);

-- The active-window scan: currently-in-effect windows (starts <= now < ends), live only.
create index idx_maintenance_active on maintenance_window (starts_at, ends_at)
    where deleted_at is null;

create index idx_maintenance_org on maintenance_window (org_id, starts_at desc)
    where deleted_at is null;

create index idx_maintenance_deleted on maintenance_window (deleted_at)
    where deleted_at is not null;
