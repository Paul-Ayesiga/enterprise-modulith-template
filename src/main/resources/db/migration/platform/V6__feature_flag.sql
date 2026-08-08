-- Feature flags (settings module owns configuration; replaces Togglz — no Boot 4 build).

create table feature_flag
(
    id          uuid primary key,
    flag_key    varchar(150) not null unique,
    enabled     boolean      not null,
    description text,
    version     bigint       not null,
    created_at  timestamptz  not null,
    created_by  uuid        ,
    updated_at  timestamptz,
    updated_by  uuid        
);
