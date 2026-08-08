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
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        
);

create index idx_notification_log_created_at on notification_log (created_at desc);

-- An in-app notification is addressed to a PERSON, not to a channel address: there is nowhere to
-- deliver it but this platform's own UI. The column was `recipient varchar(150)` and held a Keycloak
-- subject behind a neutral name and a width that matched no identifier anyone actually stored — the
-- kind of column a reader has to open the sender to understand. It is person.id now, and it says so.
--
-- The ABSENCE of org_id is deliberate and stays: a notification belongs to the person who receives
-- it, not to the tenant they were acting in, which is why per-org retention has no meaning here.
create table in_app_notification
(
    id         uuid         primary key,
    person_id  uuid         not null,   -- person.id — SOFT ref, no FK (identity is another module)
    subject    varchar(255) not null,   -- the notification's SUBJECT LINE, not a principal
    body       text,
    read_at    timestamptz,
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        
);

-- Read path: a person's notifications, newest first (matches the cursor sort createdAt desc, id desc).
create index idx_in_app_notification_person on in_app_notification (person_id, created_at desc, id desc);
