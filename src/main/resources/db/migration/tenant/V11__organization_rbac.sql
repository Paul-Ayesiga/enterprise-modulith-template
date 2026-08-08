-- Organization module, tenant half: org-scoped RBAC. Permissions are a fixed enum; roles are
-- editable bundles of permissions (system roles are immutable). `org_id` here means
-- organization.id — the tenant key defined by the platform half, never a Keycloak identifier.
--
-- FK RULE (AGENTS §1): organization, external_organization, org_role, role_permission and membership
-- are ONE module, so org_id may finally be a real foreign key — and was, until the tenant boundary
-- turned out to be a second axis §1 was not looking at. membership.person_id is the case that proved
-- the module rule: person lives in the identity module, so it stays a SOFT ref with no FK.
--
-- AND org_id IS NOW ONE TOO. `org_role.org_id → organization(id)` and `membership.org_id →
-- organization(id)` were real foreign keys when this file was one file; V53 cut them because
-- `organization` is platform-tier and these rows are the tenant's. The split moves that cut from V53
-- to HERE, and it is not a preference: a foreign key cannot be created across the boundary in the
-- first place — `organization` is not on this sequence's search_path, and after Phase 7 it is not
-- even in the same database. So the columns are declared as soft refs and V53's tenant half no
-- longer has a constraint to drop; its header says so. What guarantees the relationship instead is
-- V53's answer, unchanged: the schema the row is in IS the assertion the FK used to make, and it is
-- the one that survives the org moving.
--
-- SOFT DELETE is declared here rather than retrofitted in V17, for the reason given in V10's header.
--
-- SPLIT (ADR 0010 §4.1): this is the tenant half of V11. Its sibling is db/migration/platform/V11__organization_rbac.sql.

-- ---------------------------------------------------------------------------------------------
-- RBAC: roles are bundles of permissions, memberships bind a person to one role in one org.
-- ---------------------------------------------------------------------------------------------

create table org_role
(
    id          uuid         not null primary key,
    org_id      uuid         not null,          -- organization.id — SOFT ref, no FK: platform tier
    code        varchar(64)  not null,
    name        varchar(120) not null,
    system_role boolean      not null,
    description text,
    version     bigint       not null,
    created_at  timestamptz  not null,
    created_by  uuid        ,
    updated_at  timestamptz,
    updated_by  uuid        ,
    deleted_at  timestamptz
);

-- Partial: deleting the role 'AUDITOR' must not permanently forbid creating another one in that org.
create unique index uq_org_role_org_code_live on org_role (org_id, code)
    where deleted_at is null;

create index idx_org_role_org on org_role (org_id);

create index idx_org_role_deleted on org_role (deleted_at)
    where deleted_at is not null;

create table role_permission
(
    role_id    uuid        not null references org_role (id) on delete cascade,
    permission varchar(64) not null,
    primary key (role_id, permission)
);

create table membership
(
    id         uuid        not null primary key,
    org_id     uuid        not null,            -- organization.id — SOFT ref, no FK: platform tier
    person_id  uuid        not null,            -- person.id — SOFT ref, no FK: identity is another
                                                -- module (AGENTS §1). It is our uuid now, not a
                                                -- Keycloak sub, but it is still a soft ref
    role_id    uuid        not null references org_role (id),
    status     varchar(20) not null,            -- ACTIVE | SUSPENDED
    version    bigint      not null,
    created_at timestamptz not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        ,
    deleted_at timestamptz
);

-- Partial for a reason worth stating: re-inviting someone previously removed must be possible, so a
-- person may legitimately hold one live plus N soft-deleted rows per org. That history is the record
-- of who was in this org and when — a backfill or a dedupe on (org_id, person_id) destroys it.
create unique index uq_membership_org_person_live on membership (org_id, person_id)
    where deleted_at is null;

-- The resolver's reverse lookup: every org a person belongs to.
create index idx_membership_person on membership (person_id);

create index idx_membership_role on membership (role_id);

create index idx_membership_deleted on membership (deleted_at)
    where deleted_at is not null;
