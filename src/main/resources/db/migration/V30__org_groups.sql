-- Org user groups: a named funnel that grants ITS role to every member, IN ADDITION to each
-- member's direct membership role. The permission resolver unions them, so a MEMBER who is also in
-- an "Auditors" group (role AUDITOR) holds MEMBER ∪ AUDITOR. A group is a soft-deletable aggregate
-- (user-managed configuration, the org_role species); group membership rows are element children.

-- Both references below are real foreign keys: org_group, organization and org_role are the same
-- module (AGENTS §1 forbids CROSS-module FKs, not intra-module ones). role_id went unenforced when it
-- was written, sitting next to membership.role_id which was enforced — an inconsistency with no
-- reason behind it. org_id could not be enforced at all until organization.id became the tenant key
-- (V11); it held a Keycloak identifier, and there is nothing to point a foreign key at in Keycloak.
create table org_group
(
    id         uuid         not null,
    org_id     uuid         not null references organization (id),
    name       varchar(100) not null,
    role_id    uuid         not null references org_role (id),   -- the org_role this group confers
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        ,
    deleted_at timestamptz,
    primary key (id)
);

-- One live group per (org, name).
create unique index uq_org_group_org_name_live on org_group (org_id, name)
    where deleted_at is null;

create index idx_org_group_org on org_group (org_id, created_at desc, id desc)
    where deleted_at is null;

create index idx_org_group_deleted on org_group (deleted_at)
    where deleted_at is not null;

-- person_id is a SOFT ref with no FK: person is the identity module. It is half the primary key, so
-- it was the sharpest case of the old shape — a Keycloak subject was not merely referenced here, it
-- was load-bearing key data in a table that could not point at the thing it keyed on.
create table org_group_member
(
    group_id  uuid not null references org_group (id) on delete cascade,
    person_id uuid not null,
    primary key (group_id, person_id)
);

-- The resolver's reverse lookup: every group a person is in, org-scoped.
create index idx_org_group_member_person on org_group_member (person_id);
