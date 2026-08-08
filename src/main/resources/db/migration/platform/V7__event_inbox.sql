-- Idempotent-consumer inbox: listeners record (listener_id, message_id) before side effects;
-- a duplicate delivery (registry re-publish after restart, at-least-once semantics) is skipped.

create table event_inbox
(
    listener_id  varchar(200) not null,
    message_id   varchar(200) not null,
    processed_at timestamptz  not null,
    primary key (listener_id, message_id)
);
