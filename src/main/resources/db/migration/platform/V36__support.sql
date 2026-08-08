-- Customer support: tickets a tenant opens, the messages on them, per-priority SLA targets, and the
-- escalation the breach job drives. A ticket is a soft-deletable aggregate; messages are an
-- append-only child (cascade FK) — internal-flagged messages are platform-only notes never shown to
-- the tenant. sla_policy is seeded reference data (the RoleSeeder pattern), not soft-deletable.
--
-- SPLIT (ADR 0010 §4.1): this is the platform half of V36. Its sibling is db/migration/tenant/V36__support.sql.

create table sla_policy
(
    id                     uuid        not null primary key,
    priority               varchar(2)  not null,      -- P1 | P2 | P3 | P4
    first_response_minutes int         not null,
    resolution_minutes     int         not null,
    version                bigint      not null,
    created_at             timestamptz not null,
    created_by             uuid        ,
    updated_at             timestamptz,
    updated_by             uuid        ,
    constraint uq_sla_policy_priority unique (priority)
);
