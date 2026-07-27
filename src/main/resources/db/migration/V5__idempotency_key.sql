-- HTTP idempotency-key store: a row is claimed BEFORE the handler runs (response_status null =
-- in progress), then completed with the response. Purged by the scheduler after a retention window.
create table idempotency_key
(
    idem_key        varchar(128) not null primary key,
    request_hash    varchar(64)  not null,
    response_status int,
    response_body   text,
    content_type    varchar(100),
    created_at      timestamptz  not null
);
