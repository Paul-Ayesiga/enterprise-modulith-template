-- Billing linkage: which Kill Bill account carries an organization's money. Kill Bill is the
-- BILLING system of record (accounts, subscriptions, invoices, payments live THERE, keyed back to
-- us by an externalKey); this row is the local projection that makes "is this org billed, and
-- where" answerable without a remote round trip — the external_organization pattern (V11), where a
-- foreign system's key space lives in an adapter row instead of inside the module that uses it. The
-- subscription module stays the ENTITLEMENT authority: Kill Bill events reconcile INTO it, so a
-- paid plan always arrives through the same assign path a manual comp does.
--
-- THE externalKey OVER THERE IS NOT org_id, and the difference is deliberate rather than drift. Kill
-- Bill's accounts already exist keyed by the Keycloak organization id, and re-keying live billing
-- accounts at a third party to match an internal rename is risk with no return — money systems do
-- not get churned for tidiness. The gateway resolves that id through external_organization on the
-- way out; org_id below is organization.id, ours, and the two are joined by a row, not by luck.

create table billing_account
(
    id            uuid        not null primary key,
    org_id        uuid        not null,             -- organization.id — soft ref (no FK, module boundary)
    kb_account_id uuid        not null,             -- Kill Bill accountId (their key space, not ours)
    version       bigint      not null,
    created_at    timestamptz not null,
    created_by    uuid        ,
    updated_at    timestamptz,
    updated_by    uuid        ,
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
