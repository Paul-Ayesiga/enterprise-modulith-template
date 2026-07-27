-- Durable delivery queue for at-least-once, non-blocking fan-out. Replaces the synchronous
-- notification_log with a claimable work queue: background workers claim eligible rows with
-- SELECT ... FOR UPDATE SKIP LOCKED, deliver with bounded concurrency (outside any DB transaction),
-- and retry with backoff / dead-letter. SKIP LOCKED lets N app instances share the load safely.

drop table if exists notification_log;

create table notification_delivery
(
    id              uuid primary key,
    channel         varchar(20)  not null,
    recipient       text         not null,   -- email / phone / user-id / webhook or Slack URL (URLs exceed 320)
    subject         varchar(255) not null,
    body            text,
    status          varchar(20)  not null,   -- PENDING | PROCESSING | SENT | FAILED
    attempts        int          not null default 0,
    max_attempts    int          not null,
    next_attempt_at timestamptz  not null,
    locked_at       timestamptz,
    last_error      text,
    created_at      timestamptz  not null
);

-- Claim path: eligible rows ordered by next_attempt_at (partial index keeps it tight as SENT rows pile up).
create index idx_notification_delivery_claim
    on notification_delivery (status, next_attempt_at)
    where status in ('PENDING', 'PROCESSING');
