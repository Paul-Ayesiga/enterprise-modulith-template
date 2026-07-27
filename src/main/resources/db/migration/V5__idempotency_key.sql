-- HTTP idempotency-key store: a row is claimed BEFORE the handler runs (response_status null =
-- in progress), then completed with the response. Keys are scoped PER PRINCIPAL — one user's key
-- never replays to (or blocks) another user. Purged by the scheduler after a retention window.
create table idempotency_key
(
    principal       varchar(200) not null,
    idem_key        varchar(128) not null,
    request_hash    varchar(64)  not null,
    response_status int,
    response_body   text,
    content_type    varchar(100),
    created_at      timestamptz  not null,
    primary key (principal, idem_key)
);
