-- Settings module: system-wide key/value configuration (modules own their data).
create table setting
(
    id            uuid primary key,
    setting_key   varchar(150) not null unique,
    setting_value text         not null,
    description   text,
    version       bigint       not null,
    created_at    timestamptz  not null,
    created_by    varchar(100),
    updated_at    timestamptz,
    updated_by    varchar(100)
);
