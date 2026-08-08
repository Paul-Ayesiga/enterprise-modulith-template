-- Audit module: append-only trail of state changes, built by consuming domain events from every
-- module. org_id holds organization.id and is null for platform-level events (identity, settings).
-- actor_person_id is reserved for the acting principal once events carry it (today's events record
-- the change, not who made it).
--
-- WHY actor_person_id IS NULLABLE AND STAYS NULLABLE: a row with no actor is a system-triggered
-- change, and that is information, not a missing value. The same null now also covers a MACHINE: an
-- org-owned API key has no person behind it, and manufacturing one would put a robot in member lists
-- and GDPR erasure sweeps. So this column answers "which human is accountable", and answers "nobody"
-- honestly. Distinguishing a scheduled job from a machine key on these rows needs a typed actor
-- triple (kind + id + label), which is a slice of its own, not a widened column.
--
-- WHY target STAYS TEXT: it is polymorphic, read through `action` — a person id for member actions,
-- a ticket uuid, a composite orgId:priority, a setting key. Splitting out a typed target column
-- would make every writer choose between two columns on a contract encoded in a string, and nothing
-- joins on it today.
--
-- SPLIT (ADR 0010 §4.1): this is the platform half of V13. Its sibling is db/migration/tenant/V13__audit_log.sql.

create table audit_log
(
    id              uuid primary key,
    org_id          uuid,                        -- organization.id; null for platform-level (non-org) events
    action          varchar(80)  not null,       -- e.g. organization.member_added, settings.changed
    actor_person_id uuid,                        -- person.id of the accountable human; null = system or machine
    target          varchar(320),                -- the affected entity (person id, alias, role id, setting key)
    detail          varchar(500),                -- salient extra context (role code, new status, value…)
    occurred_at     timestamptz  not null,       -- when the change happened (event occurredAt)
    version         bigint       not null,
    created_at      timestamptz  not null,       -- when it was recorded (the audit timeline)
    created_by      uuid        ,
    updated_at      timestamptz,
    updated_by      uuid        
);

-- Newest-first listing globally and per org; the keyset cursor sorts on (created_at desc, id desc).
create index idx_audit_created on audit_log (created_at desc, id desc);

create index idx_audit_org_created on audit_log (org_id, created_at desc, id desc);

create index idx_audit_action on audit_log (action);
