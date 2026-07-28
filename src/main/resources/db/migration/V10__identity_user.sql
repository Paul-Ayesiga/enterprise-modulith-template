-- Identity module: local projection of Keycloak users + admin-provisioning lifecycle (no JIT).
create table app_user
(
    id             uuid primary key,
    subject        varchar(64)  not null unique,   -- Keycloak user id (JWT sub)
    email          varchar(320) not null,
    status         varchar(20)  not null,          -- INVITED | ACTIVE | DISABLED
    provisioned_at timestamptz  not null,
    activated_at   timestamptz,
    version        bigint       not null,
    created_at     timestamptz  not null,
    created_by     varchar(100),
    updated_at     timestamptz,
    updated_by     varchar(100)
);

create index idx_app_user_email on app_user (email);
