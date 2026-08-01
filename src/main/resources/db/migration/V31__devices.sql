-- A user's registered devices: what they sign in from, and whether the org trusts it. push_token
-- is nullable and forward-looking (a future notification PUSH channel reads it). A device is a
-- soft-deletable aggregate — "revoke this device" keeps the row as the trail. last_seen_at is
-- stamped throttled off the request path.

create table user_device
(
    id           uuid         not null primary key,
    subject      varchar(64)  not null,
    name         varchar(100) not null,
    kind         varchar(10)  not null,               -- BROWSER | MOBILE | CLI
    fingerprint  varchar(100) not null,               -- opaque client-supplied id (the X-Device-Id)
    push_token   varchar(300),
    trusted      boolean      not null default false,
    last_seen_at timestamptz,
    version      bigint       not null,
    created_at   timestamptz  not null,
    created_by   varchar(100),
    updated_at   timestamptz,
    updated_by   varchar(100),
    deleted_at   timestamptz
);

-- One live registration per (subject, fingerprint) — re-registering the same device updates it.
create unique index uq_user_device_subject_fingerprint_live on user_device (subject, fingerprint)
    where deleted_at is null;

create index idx_user_device_subject on user_device (subject, created_at desc, id desc)
    where deleted_at is null;

create index idx_user_device_deleted on user_device (deleted_at)
    where deleted_at is not null;
