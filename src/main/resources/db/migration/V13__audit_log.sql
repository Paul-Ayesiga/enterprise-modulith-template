-- Audit module: append-only trail of state changes, built by consuming domain events from every
-- module. org_id is null for platform-level events (identity, settings). actor is reserved for the
-- acting principal once events carry it (today's events record the change, not who made it).
create table audit_log
(
    id          uuid primary key,
    org_id      uuid,                        -- null for platform-level (non-org) events
    action      varchar(80)  not null,       -- e.g. organization.member_added, settings.changed
    actor       varchar(64),                 -- acting principal (reserved; null until events carry it)
    target      varchar(320),                -- the affected entity (subject, alias, role id, setting key)
    detail      varchar(500),                -- salient extra context (role code, new status, value…)
    occurred_at timestamptz  not null,       -- when the change happened (event occurredAt)
    version     bigint       not null,
    created_at  timestamptz  not null,       -- when it was recorded (the audit timeline)
    created_by  varchar(100),
    updated_at  timestamptz,
    updated_by  varchar(100)
);

-- Newest-first listing globally and per org; the keyset cursor sorts on (created_at desc, id desc).
create index idx_audit_created on audit_log (created_at desc, id desc);
create index idx_audit_org_created on audit_log (org_id, created_at desc, id desc);
create index idx_audit_action on audit_log (action);
