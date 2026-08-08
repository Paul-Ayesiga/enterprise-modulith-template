-- Customer support: tickets a tenant opens, the messages on them, per-priority SLA targets, and the
-- escalation the breach job drives. A ticket is a soft-deletable aggregate; messages are an
-- append-only child (cascade FK) — internal-flagged messages are platform-only notes never shown to
-- the tenant. sla_policy is seeded reference data (the RoleSeeder pattern), not soft-deletable.
--
-- The three identity columns here are person.id (V10), SOFT refs with no FK — support is not the
-- identity module (AGENTS §1) — and org_id is organization.id (V11), on the same terms. Support is
-- the clearest evidence in the schema for why identity had to be ONE table: opener_person_id is a
-- member of the ticket's own tenant, assignee_person_id is a PLATFORM operator who is a member of no
-- tenant at all, and author_person_id on a message is either. Those were three subject strings
-- pointing into two different tenancy worlds, needing two lookup paths to resolve; they now resolve
-- through one.
--
-- ticket.subject is NOT one of them and never was — it is the ticket's title. It is the reason a
-- "rename every column called subject" sweep is the wrong tool for this schema, and it keeps its
-- name because the name is correct.
--
-- The overlap with created_by stays, deliberately: on creation created_by and opener_person_id name
-- the same human, but they are different KINDS of column and no longer even the same type.
-- created_by is a polymorphic actor string that may hold a person, the literal system, or
-- key:<api_key.id>; opener_person_id must be a person, and the ticket surface reads it as one.
--
-- SPLIT (ADR 0010 §4.1): this is the tenant half of V36. Its sibling is db/migration/platform/V36__support.sql.

create table ticket
(
    id                     uuid         not null primary key,
    org_id                 uuid         not null,      -- organization.id; soft ref, no FK
    opener_person_id       uuid         not null,      -- person.id; a member of THIS org
    subject                varchar(200) not null,      -- the ticket's TITLE, not a principal
    category               varchar(40),
    priority               varchar(2)   not null,      -- P1..P4
    status                 varchar(25)  not null,      -- OPEN|IN_PROGRESS|WAITING_ON_CUSTOMER|RESOLVED|CLOSED
    assignee_person_id     uuid,                       -- person.id; a PLATFORM operator, who is a
                                                       -- member of no tenant
    first_response_at      timestamptz,
    first_response_due_at  timestamptz  not null,
    resolution_due_at      timestamptz  not null,
    escalated              boolean      not null default false,
    version                bigint       not null,
    created_at             timestamptz  not null,
    created_by             uuid        ,
    updated_at             timestamptz,
    updated_by             uuid        ,
    deleted_at             timestamptz
);

-- Tenant listing (keyset createdAt desc, id desc) and the platform queue (by status/priority).
create index idx_ticket_org on ticket (org_id, created_at desc, id desc) where deleted_at is null;

create index idx_ticket_status on ticket (status, priority, created_at desc) where deleted_at is null;

-- The breach scan: open tickets whose SLA due has passed and that are not yet escalated.
create index idx_ticket_sla on ticket (resolution_due_at)
    where deleted_at is null and escalated = false and status not in ('RESOLVED', 'CLOSED');

create index idx_ticket_deleted on ticket (deleted_at) where deleted_at is not null;

-- Tenancy is inherited through ticket_id rather than denormalised — the one table in this schema that
-- already models it the right way, and nothing about the identity retarget changes that.
create table ticket_message
(
    id               uuid         not null primary key,
    ticket_id        uuid         not null references ticket (id) on delete cascade,
    author_person_id uuid         not null,               -- person.id; a tenant member or a platform
                                                          -- operator, resolved through one table
    body             text         not null,
    internal         boolean      not null default false, -- platform-only note, never shown to the
                                                          -- tenant. A VISIBILITY flag, not a
                                                          -- discriminator on who the author is
    created_at       timestamptz  not null
);

create index idx_ticket_message_ticket on ticket_message (ticket_id, created_at);
