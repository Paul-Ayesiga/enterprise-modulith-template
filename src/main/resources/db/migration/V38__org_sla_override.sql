-- Per-org SLA overrides: an org (typically enterprise, by contract) can carry tighter — or looser —
-- SLA targets than the seeded per-priority defaults. Consulted at ticket open; absent for a priority
-- means that priority's seeded sla_policy still applies. Reference/config data like sla_policy (NOT
-- soft-deletable): clearing an override is a real delete, not a tombstone.
create table org_sla_override
(
    id                     uuid        not null primary key,
    org_id                 uuid        not null,          -- soft ref (no cross-module FK)
    priority               varchar(2)  not null,          -- P1 | P2 | P3 | P4
    first_response_minutes int         not null,
    resolution_minutes     int         not null,
    version                bigint      not null,
    created_at             timestamptz not null,
    created_by             varchar(100),
    updated_at             timestamptz,
    updated_by             varchar(100),
    constraint uq_org_sla_override unique (org_id, priority)
);
