-- Payment collections through external gateways (Pesapal redirect flow, Yo! Uganda mobile-money
-- push). One row per initiated payment; the gateway is the source of truth for the outcome and the
-- row converges to it (IPN or on-read refresh).
--
-- org_id is organization.id (V11), a SOFT ref with no FK (payments and organization are different
-- modules, AGENTS §1). It is the only identity-shaped column in this table, and the two that look
-- like ones are NOT: phone_number and email are the PAYER's contact, captured per request because
-- the gateway payload requires one of them, and the payer need not be a person in this system at
-- all. They are not person_contact rows and folding them into one would invent an identity out of a
-- payment form. merchant_reference and gateway_reference are likewise foreign infrastructure —
-- our payment id stringified, and the provider's own id.
--
-- STANDING GAP, flagged and deliberately not fixed here: who INITIATED a payment is recorded nowhere
-- on this row. The table has no created_by (it never extended BaseEntity) and now no person column
-- either, so the answer lives only in audit_log. Adding one is a payments-slice decision with its own
-- entity change, not something a retarget should smuggle in.

create table payment (
    id                  uuid primary key,
    org_id              uuid not null,           -- organization.id; soft ref, no FK
    provider            varchar(32)  not null,   -- pesapal | yo-uganda
    mode                varchar(16)  not null,   -- sandbox | live (stamped at initiation)
    merchant_reference  varchar(50)  not null unique,
    gateway_reference   varchar(100),            -- Pesapal order_tracking_id / Yo TransactionReference
    amount              numeric(19,2) not null,
    currency            varchar(3)   not null,
    description         varchar(100) not null,
    phone_number        varchar(20),             -- the PAYER's contact, not a person_contact row
    email               varchar(255),            -- likewise; the payer may be no person we know
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
