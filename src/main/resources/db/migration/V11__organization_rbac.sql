-- Organization module: local projection of Keycloak organizations + org-scoped RBAC.
-- Permissions are a fixed enum; roles are editable bundles of permissions (system roles are immutable).
-- org_id everywhere is the Keycloak organization UUID (the tenant key).

create table organization
(
    id         uuid primary key,
    kc_org_id  uuid         not null unique,   -- Keycloak organization id (tenant key)
    alias      varchar(120) not null unique,
    name       varchar(200) not null,
    status     varchar(20)  not null,          -- ACTIVE | SUSPENDED
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100)
);

create table org_role
(
    id          uuid primary key,
    org_id      uuid         not null,         -- Keycloak organization id
    code        varchar(64)  not null,
    name        varchar(120) not null,
    system_role boolean      not null,
    description text,
    version     bigint       not null,
    created_at  timestamptz  not null,
    created_by  varchar(100),
    updated_at  timestamptz,
    updated_by  varchar(100),
    constraint uq_org_role_org_code unique (org_id, code)
);

create index idx_org_role_org on org_role (org_id);

create table role_permission
(
    role_id    uuid        not null references org_role (id) on delete cascade,
    permission varchar(64) not null,
    primary key (role_id, permission)
);

create table membership
(
    id           uuid primary key,
    org_id       uuid        not null,         -- Keycloak organization id
    user_subject varchar(64) not null,         -- Keycloak user id (JWT sub)
    role_id      uuid        not null references org_role (id),
    status       varchar(20) not null,         -- ACTIVE | SUSPENDED
    version      bigint      not null,
    created_at   timestamptz not null,
    created_by   varchar(100),
    updated_at   timestamptz,
    updated_by   varchar(100),
    constraint uq_membership_org_user unique (org_id, user_subject)
);

create index idx_membership_subject on membership (user_subject);
create index idx_membership_role on membership (role_id);
