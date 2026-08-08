-- HTTP idempotency-key store: a row is claimed BEFORE the handler runs (response_status null =
-- in progress), then completed with the response. Keys are scoped PER PRINCIPAL — one user's key
-- never replays to (or blocks) another user. Purged by the scheduler after a retention window.
--
-- `principal` IS THE ONE IDENTITY-BEARING COLUMN THAT DOES NOT BECOME A person_id, and it is said
-- here so a sweep for "every column holding a subject" does not collect it. It is resolved at the
-- edge, stored opaquely, compared only for equality, half a composite primary key, and gone within
-- the retention window — so retyping it buys nothing and costs a rewrite of the key space. The
-- WRITER changes instead: it now stores person.id where it used to store a Keycloak sub (plus the
-- `anonymous` sentinel for unauthenticated calls), and stale rows age out on their own.
--
-- Whatever it holds must be IMMUTABLE for the life of the row. That is why it was never the
-- username: a recycled identifier would inherit and replay the previous holder's responses, which
-- is a cross-account disclosure wearing a cache hit's clothes.

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
