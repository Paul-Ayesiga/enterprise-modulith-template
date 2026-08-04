-- Self-service signup: an email-verification handshake in front of the standard org-provisioning
-- path. One row per request; the token is stored HASHED (a DB leak must not mint organizations);
-- a new request for the same email supersedes (deletes) its pending predecessors.
create table signup_request (
    id           uuid primary key,
    email        varchar(255) not null,
    org_name     varchar(80)  not null,
    first_name   varchar(60),
    last_name    varchar(60),
    token_hash   varchar(64)  not null unique,
    status       varchar(16)  not null,       -- PENDING | COMPLETED
    expires_at   timestamptz  not null,
    created_at   timestamptz  not null,
    completed_at timestamptz,
    org_id       uuid
);
create index idx_signup_email on signup_request (email, status);
