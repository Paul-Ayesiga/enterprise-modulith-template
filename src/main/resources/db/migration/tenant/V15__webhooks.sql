-- Webhooks module: per-org outbound event subscriptions + a durable, signed delivery queue/log.
--
-- org_id is organization.id — a soft ref, no FK across the module boundary. It is also one of the
-- sixteen owner columns the compliance purge joins against legal_hold in hand-written SQL, and that
-- join fails SILENTLY: a mismatched key space matches zero rows, so the nightly purge would resume
-- hard-deleting subscriptions a court said to keep and nothing would log a word. If the tenant key
-- ever moves again, this column and legal_hold move in the SAME migration.

create table webhook_subscription
(
    id          uuid          primary key,
    org_id      uuid          not null,               -- organization.id — the tenant that owns this
    url         varchar(2048) not null,               -- caller-supplied target (SSRF-guarded at send)
    secret      varchar(200)  not null,               -- HMAC-SHA256 signing secret
    event_types text          not null,               -- comma-joined event codes this endpoint wants
    status      varchar(20)   not null,               -- ACTIVE | DISABLED
    version     bigint        not null,
    created_at  timestamptz   not null,
    created_by  uuid        ,
    updated_at  timestamptz,
    updated_by  uuid        
);

create index idx_webhook_sub_org on webhook_subscription (org_id);

create table webhook_delivery
(
    id              uuid         primary key,
    subscription_id uuid         not null references webhook_subscription (id) on delete cascade,
    org_id          uuid         not null,            -- organization.id, denormalised from the parent
    event_type      varchar(80)  not null,
    payload         text         not null,            -- the signed JSON body
    status          varchar(20)  not null,            -- PENDING | PROCESSING | DELIVERED | FAILED
    attempts        int          not null,
    max_attempts    int          not null,
    next_attempt_at timestamptz  not null,
    locked_at       timestamptz,
    last_error      varchar(1000),
    response_status int,
    created_at      timestamptz  not null,
    delivered_at    timestamptz
);

-- Index-friendly claim: due-PENDING by schedule, and stale-PROCESSING reclaim, as partial indexes.
create index idx_webhook_del_due on webhook_delivery (next_attempt_at) where status = 'PENDING';

create index idx_webhook_del_stale on webhook_delivery (locked_at) where status = 'PROCESSING';

-- Newest-first delivery log per subscription (keyset cursor sorts on created_at desc, id desc).
create index idx_webhook_del_sub on webhook_delivery (subscription_id, created_at desc, id desc);
