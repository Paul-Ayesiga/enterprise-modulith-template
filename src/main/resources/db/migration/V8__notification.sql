-- Notification module: audit trail of channel deliveries + persisted in-app notifications.

create table notification_log
(
    id         uuid primary key,
    channel    varchar(20)  not null,
    recipient  varchar(320) not null,
    subject    varchar(255) not null,
    status     varchar(20)  not null,
    error      text,
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100)
);

create index idx_notification_log_created_at on notification_log (created_at desc);

create table in_app_notification
(
    id         uuid primary key,
    recipient  varchar(150) not null,
    subject    varchar(255) not null,
    body       text,
    read_at    timestamptz,
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by varchar(100),
    updated_at timestamptz,
    updated_by varchar(100)
);

-- Read path: a user's notifications, newest first (matches the cursor sort createdAt desc, id desc).
create index idx_in_app_notification_recipient on in_app_notification (recipient, created_at desc, id desc);
