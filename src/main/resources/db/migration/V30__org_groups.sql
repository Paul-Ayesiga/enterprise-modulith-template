-- Org user groups: a named funnel that grants ITS role to every member, IN ADDITION to each
-- member's direct membership role. The permission resolver unions them, so a MEMBER who is also in
-- an "Auditors" group (role AUDITOR) holds MEMBER ∪ AUDITOR. A group is a soft-deletable aggregate
-- (user-managed configuration, the org_role species); group membership rows are element children.

create table org_group
(
    id         uuid         not null,
    org_id     uuid         not null,
    name       varchar(100) not null,
    role_id    uuid         not null,               -- the org_role this group confers (same-module ref)
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100),
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

create table org_group_member
(
    group_id     uuid        not null references org_group (id) on delete cascade,
    user_subject varchar(64) not null,
    primary key (group_id, user_subject)
);

-- The resolver's reverse lookup: every group a subject is in, org-scoped.
create index idx_org_group_member_subject on org_group_member (user_subject);
