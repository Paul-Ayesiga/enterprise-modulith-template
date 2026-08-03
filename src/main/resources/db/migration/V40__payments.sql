-- Payment collections through external gateways (Pesapal redirect flow, Yo! Uganda mobile-money
-- push). One row per initiated payment; the gateway is the source of truth for the outcome and the
-- row converges to it (IPN or on-read refresh).
create table payment (
    id                  uuid primary key,
    org_id              uuid not null,
    provider            varchar(32)  not null,   -- pesapal | yo-uganda
    mode                varchar(16)  not null,   -- sandbox | live (stamped at initiation)
    merchant_reference  varchar(50)  not null unique,
    gateway_reference   varchar(100),            -- Pesapal order_tracking_id / Yo TransactionReference
    amount              numeric(19,2) not null,
    currency            varchar(3)   not null,
    description         varchar(100) not null,
    phone_number        varchar(20),
    email               varchar(255),
    status              varchar(24)  not null,   -- PENDING | COMPLETED | FAILED | REVERSED | INVALID | INDETERMINATE
    status_detail       varchar(255),
    confirmation_code   varchar(64),
    redirect_url        varchar(1024),
    created_at          timestamptz  not null,
    updated_at          timestamptz  not null,
    version             bigint       not null
);

create index idx_payment_org         on payment (org_id, created_at desc);
create index idx_payment_gateway_ref on payment (gateway_reference);
