-- User self-service identity enrichment: profile (display fields + avatar key), preferences
-- (small per-user key/value), contacts (element rows on the profile). The profile is an aggregate
-- (soft-deletable, THIRTEENTH); preferences are the idempotency-key species — plain rows, no
-- lifecycle beyond their user; contacts follow the profile (role_permission's pattern).

create table user_profile
(
    id           uuid         not null primary key,
    subject      varchar(64)  not null,               -- Keycloak sub; soft ref, never a username
    display_name varchar(150),
    phone        varchar(30),
    timezone     varchar(50),
    locale       varchar(20),
    avatar_key   varchar(300),                        -- files-port key (avatar/u/<subject>/…)
    version      bigint       not null,
    created_at   timestamptz  not null,
    created_by   varchar(100),
    updated_at   timestamptz,
    updated_by   varchar(100),
    deleted_at   timestamptz
);

create unique index uq_user_profile_subject_live on user_profile (subject)
    where deleted_at is null;

create index idx_user_profile_deleted on user_profile (deleted_at)
    where deleted_at is not null;

create table user_contact
(
    profile_id    uuid         not null references user_profile (id) on delete cascade,
    kind          varchar(10)  not null,              -- EMAIL | PHONE | OTHER
    contact_value varchar(150) not null,
    label         varchar(50),
    is_primary    boolean      not null default false
);

create index idx_user_contact_profile on user_contact (profile_id);

create table user_preference
(
    subject    varchar(64)  not null,
    pref_key   varchar(100) not null,
    pref_value varchar(500) not null,
    updated_at timestamptz  not null,
    primary key (subject, pref_key)
);
