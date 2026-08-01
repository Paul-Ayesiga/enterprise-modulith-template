-- Customer support: tickets a tenant opens, the messages on them, per-priority SLA targets, and the
-- escalation the breach job drives. A ticket is a soft-deletable aggregate; messages are an
-- append-only child (cascade FK) — internal-flagged messages are platform-only notes never shown to
-- the tenant. sla_policy is seeded reference data (the RoleSeeder pattern), not soft-deletable.

create table sla_policy
(
    id                     uuid        not null primary key,
    priority               varchar(2)  not null,      -- P1 | P2 | P3 | P4
    first_response_minutes int         not null,
    resolution_minutes     int         not null,
    version                bigint      not null,
    created_at             timestamptz not null,
    created_by             varchar(100),
    updated_at             timestamptz,
    updated_by             varchar(100),
    constraint uq_sla_policy_priority unique (priority)
);

create table ticket
(
    id                     uuid         not null primary key,
    org_id                 uuid         not null,
    opener_subject         varchar(64)  not null,
    subject                varchar(200) not null,
    category               varchar(40),
    priority               varchar(2)   not null,      -- P1..P4
    status                 varchar(25)  not null,       -- OPEN|IN_PROGRESS|WAITING_ON_CUSTOMER|RESOLVED|CLOSED
    assignee_subject       varchar(64),                 -- platform-support subject
    first_response_at      timestamptz,
    first_response_due_at  timestamptz  not null,
    resolution_due_at      timestamptz  not null,
    escalated              boolean      not null default false,
    version                bigint       not null,
    created_at             timestamptz  not null,
    created_by             varchar(100),
    updated_at             timestamptz,
    updated_by             varchar(100),
    deleted_at             timestamptz
);

-- Tenant listing (keyset createdAt desc, id desc) and the platform queue (by status/priority).
create index idx_ticket_org on ticket (org_id, created_at desc, id desc) where deleted_at is null;
create index idx_ticket_status on ticket (status, priority, created_at desc) where deleted_at is null;
-- The breach scan: open tickets whose SLA due has passed and that are not yet escalated.
create index idx_ticket_sla on ticket (resolution_due_at)
    where deleted_at is null and escalated = false and status not in ('RESOLVED', 'CLOSED');
create index idx_ticket_deleted on ticket (deleted_at) where deleted_at is not null;

create table ticket_message
(
    id             uuid         not null primary key,
    ticket_id      uuid         not null references ticket (id) on delete cascade,
    author_subject varchar(64)  not null,
    body           text         not null,
    internal       boolean      not null default false, -- platform-only note, never shown to the tenant
    created_at     timestamptz  not null
);

create index idx_ticket_message_ticket on ticket_message (ticket_id, created_at);
