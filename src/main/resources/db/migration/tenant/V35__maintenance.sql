-- Scheduled maintenance windows. ANNOUNCE = banner metadata only (clients show a notice); RESTRICT
-- = during the window, org-scoped WRITES to the covered scope answer 503 + Retry-After (reads pass).
-- Scope platform (org_id null) covers everyone; an org-scoped window covers one tenant. A window is
-- a soft-deletable aggregate — cancelling one keeps the record it existed.
--
-- org_id is organization.id (V11), a SOFT ref with no FK (maintenance and organization are different
-- modules, AGENTS §1). This is the one tenant column in the schema read on the hot request path by a
-- SAFETY control: the maintenance filter takes the caller's active org and asks this table whether a
-- window covers it. Stored value and compared value must therefore be the same key — if the edge
-- ever hands the filter a Keycloak organization id while these rows hold organization.id, every
-- RESTRICT window silently stops restricting, with no error on either side. A false negative on a
-- safety control is why the edge resolves organization.id BEFORE any filter runs, not inside one.
--
-- SPLIT (ADR 0010 §4.1): this is the tenant half of V35. Its sibling is db/migration/platform/V35__maintenance.sql.

create table maintenance_window
(
    id         uuid         not null primary key,
    org_id     uuid,                                  -- organization.id; null = platform-wide
    starts_at  timestamptz  not null,
    ends_at    timestamptz  not null,
    mode       varchar(10)  not null,                 -- ANNOUNCE | RESTRICT
    message    varchar(300) not null,
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        ,
    deleted_at timestamptz
);

-- The active-window scan: currently-in-effect windows (starts <= now < ends), live only.
create index idx_maintenance_active on maintenance_window (starts_at, ends_at)
    where deleted_at is null;

create index idx_maintenance_org on maintenance_window (org_id, starts_at desc)
    where deleted_at is null;

create index idx_maintenance_deleted on maintenance_window (deleted_at)
    where deleted_at is not null;
