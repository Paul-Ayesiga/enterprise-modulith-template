-- Billing linkage: which Kill Bill account carries an organization's money. Kill Bill is the
-- BILLING system of record (accounts, subscriptions, invoices, payments live THERE, keyed back to
-- us by externalKey = org id); this row is the local projection that makes "is this org billed,
-- and where" answerable without a remote round trip — the app_user/Keycloak pattern. The
-- subscription module stays the ENTITLEMENT authority: Kill Bill events reconcile INTO it, so a
-- paid plan always arrives through the same assign path a manual comp does.

create table billing_account
(
    id            uuid        not null primary key,
    org_id        uuid        not null,             -- soft ref (no FK — module boundary)
    kb_account_id uuid        not null,             -- Kill Bill accountId; externalKey over there = org_id
    version       bigint      not null,
    created_at    timestamptz not null,
    created_by    varchar(100),
    updated_at    timestamptz,
    updated_by    varchar(100),
    deleted_at    timestamptz
);

-- One live billing account per org; a soft-deleted row frees the slot (V17's partial-unique rule).
create unique index uq_billing_account_org_live on billing_account (org_id)
    where deleted_at is null;

-- Callback resolution: Kill Bill notifications carry the KB account id, not our org id.
create index idx_billing_account_kb on billing_account (kb_account_id)
    where deleted_at is null;

-- Retention purge scan (V17 pattern).
create index idx_billing_account_deleted on billing_account (deleted_at)
    where deleted_at is not null;
