-- GENERATED — do not edit. Regenerate with `python3 scripts/split-migrations.py bootstrap`.
-- SCAFFOLD WITH AN EXPIRY DATE: delete this file, its directory, and the second entry in
-- `spring.flyway.locations` when ADR 0010 Phase 4 lands the tenant migration runner.
--
-- WHY IT EXISTS. Phase 2 splits the migrations into two sequences but builds no runner for the
-- second one — that is Phase 4, deliberately, because tenant migrations must not run at boot (the
-- Helm chart gives the pod ~105 s before the kubelet kills it and Flyway runs before the servlet
-- container serves, ADR 0010 §4.2). Meanwhile every tenant-tier entity resolves unqualified against
-- `tenant_pool` from the moment the router points there, so the schema has to be complete or the
-- whole suite fails with `relation "ticket" does not exist`. Spring Boot autoconfigures exactly ONE
-- Flyway instance, so there is no second `spring.flyway.*` block to point at `db/migration/tenant`.
-- This file is the join: it replays the tenant sequence into `tenant_pool` from inside the platform
-- run, in one transaction, as the last migration.
--
-- WHY V9999 AND NOT THE NEXT FREE NUMBER. It is not a migration, it is a stand-in for a runner, and
-- taking a real number would consume one from AGENTS §4.5's single global counter and leave a hole
-- in it when Phase 4 deletes this. 9999 sorts last, which is also what it must do, and says out
-- loud that it is not part of the sequence. The counter is untouched: the next free number is the
-- one AGENTS §4.5 names.
--
-- WHY THE PROSE IS GONE. Every statement below is copied verbatim from `db/migration/tenant/`,
-- which keeps the decision rationale. Duplicating 1,300 lines of it here would create a second
-- place to read and a second place to rot. `split-migrations.py verify` regenerates this file and
-- fails if it differs from what is committed, so editing a tenant migration without regenerating
-- is caught rather than discovered in Phase 4.
--
-- WHY `set local`. It is transaction-scoped, and Flyway 12 runs an SQL migration and its own
-- `flyway_schema_history` insert in one transaction — so the path is restored below before Flyway
-- writes that row, and no later migration inherits it. A failure here rolls the whole thing back
-- and writes no history row, which is the property ADR 0010 §4.2 relies on.
set local search_path to tenant_pool;

-- ---------------------------------------------------------------- V11__organization_rbac.sql
create table org_role
(
    id          uuid         not null primary key,
    org_id      uuid         not null,          -- organization.id — SOFT ref, no FK: platform tier
    code        varchar(64)  not null,
    name        varchar(120) not null,
    system_role boolean      not null,
    description text,
    version     bigint       not null,
    created_at  timestamptz  not null,
    created_by  uuid        ,
    updated_at  timestamptz,
    updated_by  uuid        ,
    deleted_at  timestamptz
);
create unique index uq_org_role_org_code_live on org_role (org_id, code)
    where deleted_at is null;
create index idx_org_role_org on org_role (org_id);
create index idx_org_role_deleted on org_role (deleted_at)
    where deleted_at is not null;
create table role_permission
(
    role_id    uuid        not null references org_role (id) on delete cascade,
    permission varchar(64) not null,
    primary key (role_id, permission)
);
create table membership
(
    id         uuid        not null primary key,
    org_id     uuid        not null,            -- organization.id — SOFT ref, no FK: platform tier
    person_id  uuid        not null,            -- person.id — SOFT ref, no FK: identity is another
                                                -- module (AGENTS §1). It is our uuid now, not a
                                                -- Keycloak sub, but it is still a soft ref
    role_id    uuid        not null references org_role (id),
    status     varchar(20) not null,            -- ACTIVE | SUSPENDED
    version    bigint      not null,
    created_at timestamptz not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        ,
    deleted_at timestamptz
);
create unique index uq_membership_org_person_live on membership (org_id, person_id)
    where deleted_at is null;
create index idx_membership_person on membership (person_id);
create index idx_membership_role on membership (role_id);
create index idx_membership_deleted on membership (deleted_at)
    where deleted_at is not null;

-- ---------------------------------------------------------------- V13__audit_log.sql
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
create index idx_audit_created on audit_log (created_at desc, id desc);
create index idx_audit_org_created on audit_log (org_id, created_at desc, id desc);
create index idx_audit_action on audit_log (action);

-- ---------------------------------------------------------------- V14__audit_log_state.sql
alter table audit_log drop column detail;
alter table audit_log add column from_state varchar(1000);
alter table audit_log add column to_state   varchar(1000);

-- ---------------------------------------------------------------- V15__webhooks.sql
create table webhook_subscription
(
    id          uuid          primary key,
    org_id      uuid          not null,               -- organization.id — the tenant that owns this
    url         varchar(2048) not null,               -- caller-supplied target (SSRF-guarded at send)
    secret      varchar(200)  not null,               -- HMAC-SHA256 signing secret
    event_types text          not null,               -- comma-joined event codes this endpoint wants
    status      varchar(20)   not null,               -- ACTIVE | DISABLED
    version     bigint        not null,
    created_at  timestamptz   not null,
    created_by  uuid        ,
    updated_at  timestamptz,
    updated_by  uuid        
);
create index idx_webhook_sub_org on webhook_subscription (org_id);
create table webhook_delivery
(
    id              uuid         primary key,
    subscription_id uuid         not null references webhook_subscription (id) on delete cascade,
    org_id          uuid         not null,            -- organization.id, denormalised from the parent
    event_type      varchar(80)  not null,
    payload         text         not null,            -- the signed JSON body
    status          varchar(20)  not null,            -- PENDING | PROCESSING | DELIVERED | FAILED
    attempts        int          not null,
    max_attempts    int          not null,
    next_attempt_at timestamptz  not null,
    locked_at       timestamptz,
    last_error      varchar(1000),
    response_status int,
    created_at      timestamptz  not null,
    delivered_at    timestamptz
);
create index idx_webhook_del_due on webhook_delivery (next_attempt_at) where status = 'PENDING';
create index idx_webhook_del_stale on webhook_delivery (locked_at) where status = 'PROCESSING';
create index idx_webhook_del_sub on webhook_delivery (subscription_id, created_at desc, id desc);

-- ---------------------------------------------------------------- V16__org_role_owner_only.sql
update org_role
set system_role = false,
    updated_at  = now(),
    updated_by  = null
where system_role = true
  and code in ('ADMIN', 'MEMBER');

-- ---------------------------------------------------------------- V17__soft_delete.sql
alter table webhook_subscription add column deleted_at timestamptz;
create index idx_webhook_subscription_deleted on webhook_subscription (deleted_at) where deleted_at is not null;

-- ---------------------------------------------------------------- V19__audit_log_impersonation.sql
alter table audit_log add column on_behalf_of_person_id uuid;
alter table audit_log add column impersonation_id       uuid;
create index idx_audit_on_behalf_of on audit_log (on_behalf_of_person_id)
    where on_behalf_of_person_id is not null;

-- ---------------------------------------------------------------- V20__audit_fix_indexes.sql
create index idx_membership_org_recent
    on membership (org_id, created_at desc, id desc) where deleted_at is null;
create index idx_org_role_org_recent
    on org_role (org_id, created_at desc, id desc) where deleted_at is null;
create index idx_webhook_subscription_org_recent
    on webhook_subscription (org_id, created_at desc, id desc) where deleted_at is null;
create index idx_audit_log_occurred on audit_log (occurred_at);
create index idx_webhook_delivery_terminal
    on webhook_delivery (created_at) where status in ('DELIVERED', 'FAILED');

-- ---------------------------------------------------------------- V23__document.sql
create table document
(
    id              uuid primary key,
    org_id          uuid,                       -- organization.id; null = personal document (owner-scoped)
    owner_person_id uuid         not null,      -- person.id — SOFT ref, no FK (identity is another module)
    storage_key     varchar(300) not null,      -- soft ref into the files namespace; bytes live behind the port
    name            varchar(255) not null,
    content_type    varchar(100) not null,
    size_bytes      bigint       not null,
    source          varchar(20)  not null,      -- UPLOAD | EXCHANGE
    version         bigint       not null,
    created_at      timestamptz  not null,
    created_by      uuid        ,
    updated_at      timestamptz,
    updated_by      uuid        ,
    deleted_at      timestamptz
);
create unique index uq_document_storage_key on document (storage_key) where deleted_at is null;
create index idx_document_org_recent on document (org_id, created_at desc, id desc) where deleted_at is null;
create index idx_document_owner_recent on document (owner_person_id, created_at desc, id desc)
    where deleted_at is null and org_id is null;
create index idx_document_deleted on document (deleted_at) where deleted_at is not null;

-- ---------------------------------------------------------------- V24__exchange.sql
create table exchange_job
(
    id                  uuid         primary key,
    org_id              uuid,                          -- organization.id — tenant scope (soft ref); null = platform-scoped handler
    requester_person_id uuid         not null,         -- person.id; per-record escalation checks resolve THIS person's permissions at processing time
    job_type            varchar(10)  not null,         -- IMPORT | EXPORT
    handler             varchar(60)  not null,         -- ExchangeHandler id
    format              varchar(10)  not null,         -- CSV | JSONL
    status              varchar(30)  not null,         -- PENDING | VALIDATING | PROCESSING | COMPLETED | COMPLETED_WITH_ERRORS | FAILED | CANCELLED
    source_key          varchar(300),                  -- import source (files namespace)
    result_key          varchar(300),                  -- export output
    error_report_key    varchar(300),                  -- row-addressed error CSV, when failures happened
    processed           bigint       not null default 0,
    failed              bigint       not null default 0,
    next_offset         bigint       not null default 0,
    attempts            int          not null default 0,
    cancel_requested    boolean      not null default false,
    locked_at           timestamptz,
    last_error          text,
    created_at          timestamptz  not null,
    updated_at          timestamptz
);
create index idx_exchange_job_claim on exchange_job (status, created_at)
    where status in ('PENDING', 'VALIDATING', 'PROCESSING');
create index idx_exchange_job_org_recent on exchange_job (org_id, created_at desc, id desc);
create index idx_exchange_job_terminal on exchange_job (created_at)
    where status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED');
create table exchange_job_error
(
    job_id  uuid          not null references exchange_job (id) on delete cascade,
    row_num bigint        not null,
    error   varchar(500)  not null,
    primary key (job_id, row_num)
);

-- ---------------------------------------------------------------- V25__exchange_platform_completion.sql
alter table exchange_job
    add column handler_version int not null default 1;
alter table exchange_job
    alter column org_id set not null;
create table exchange_schedule
(
    id                  uuid primary key,
    org_id              uuid         not null,          -- organization.id (soft ref, no cross-module FK)
    requester_person_id uuid         not null,          -- fired jobs run AS this person (revocation-safe re-check per fire)
    handler             varchar(60)  not null,
    format              varchar(10)  not null,
    cron                varchar(120) not null,          -- Spring cron (6 fields); validated at create
    enabled             boolean      not null default true,
    next_run_at         timestamptz  not null,
    last_job_id         uuid,                           -- soft ref to exchange_job; deliberately no FK,
                                                        -- retention trims jobs and a schedule outlives them
    version             bigint       not null,
    created_at          timestamptz  not null,
    created_by          uuid        ,
    updated_at          timestamptz,
    updated_by          uuid        ,
    deleted_at          timestamptz
);
create index idx_exchange_schedule_due on exchange_schedule (next_run_at)
    where enabled and deleted_at is null;
create index idx_exchange_schedule_org on exchange_schedule (org_id, created_at desc, id desc)
    where deleted_at is null;
create index idx_exchange_schedule_deleted on exchange_schedule (deleted_at)
    where deleted_at is not null;

-- ---------------------------------------------------------------- V26__subscription.sql
create table org_subscription
(
    id                 uuid        not null primary key,
    org_id             uuid        not null,             -- organization.id — soft ref (no FK, module boundary)
    plan_id            uuid        not null,             -- plan.id — SOFT ref, no FK: platform tier
    status             varchar(20) not null,             -- ACTIVE | TRIALING | PAST_DUE | CANCELLED
    current_period_end timestamptz,                      -- null = evergreen (no billing integration yet)
    version            bigint      not null,
    created_at         timestamptz not null,
    created_by         uuid        ,
    updated_at         timestamptz,
    updated_by         uuid        ,
    deleted_at         timestamptz
);
create unique index uq_org_subscription_org_live on org_subscription (org_id)
    where deleted_at is null;
create index idx_org_subscription_deleted on org_subscription (deleted_at)
    where deleted_at is not null;

-- ---------------------------------------------------------------- V27__billing.sql
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
create unique index uq_billing_account_org_live on billing_account (org_id)
    where deleted_at is null;
create index idx_billing_account_kb on billing_account (kb_account_id)
    where deleted_at is null;
create index idx_billing_account_deleted on billing_account (deleted_at)
    where deleted_at is not null;

-- ---------------------------------------------------------------- V30__org_groups.sql
create table org_group
(
    id         uuid         not null,
    org_id     uuid         not null,                    -- organization.id — SOFT ref, no FK: platform tier
    name       varchar(100) not null,
    role_id    uuid         not null references org_role (id),   -- the org_role this group confers
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        ,
    deleted_at timestamptz,
    primary key (id)
);
create unique index uq_org_group_org_name_live on org_group (org_id, name)
    where deleted_at is null;
create index idx_org_group_org on org_group (org_id, created_at desc, id desc)
    where deleted_at is null;
create index idx_org_group_deleted on org_group (deleted_at)
    where deleted_at is not null;
create table org_group_member
(
    group_id  uuid not null references org_group (id) on delete cascade,
    person_id uuid not null,
    primary key (group_id, person_id)
);
create index idx_org_group_member_person on org_group_member (person_id);

-- ---------------------------------------------------------------- V32__security_policy.sql
create table org_security_policy
(
    id                       uuid        not null primary key,
    org_id                   uuid        not null,          -- organization.id; soft ref, no FK
    ip_allowlist             text,                          -- comma-joined CIDRs; empty = no IP restriction
    require_trusted_device   boolean     not null default false,
    session_max_age_seconds  bigint,                        -- reject tokens older than this for the org
    version                  bigint      not null,
    created_at               timestamptz not null,
    created_by               uuid        ,
    updated_at               timestamptz,
    updated_by               uuid        ,
    deleted_at               timestamptz
);
create unique index uq_org_security_policy_org_live on org_security_policy (org_id)
    where deleted_at is null;
create index idx_org_security_policy_deleted on org_security_policy (deleted_at)
    where deleted_at is not null;

-- ---------------------------------------------------------------- V33__integration_hub.sql
create table integration
(
    id         uuid         not null primary key,
    org_id     uuid,                                  -- organization.id; null = platform default
    kind       varchar(20)  not null,                 -- SMS_PROVIDER | EMAIL_PROVIDER | PAYMENT_GATEWAY
    provider   varchar(40)  not null,                 -- provider code (e.g. twilio, smtp, stripe)
    enabled    boolean      not null default true,
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        ,
    deleted_at timestamptz
);
create unique index uq_integration_org_kind_live on integration (org_id, kind)
    where deleted_at is null and org_id is not null;
create unique index uq_integration_platform_kind_live on integration (kind)
    where deleted_at is null and org_id is null;
create index idx_integration_deleted on integration (deleted_at)
    where deleted_at is not null;
create table integration_setting
(
    integration_id uuid         not null references integration (id) on delete cascade,
    setting_key    varchar(60)  not null,
    setting_value  varchar(600) not null,             -- plaintext, or enc:v1:<...> when secret
    is_secret      boolean      not null default false,
    primary key (integration_id, setting_key)
);

-- ---------------------------------------------------------------- V35__maintenance.sql
create table maintenance_window
(
    id         uuid         not null primary key,
    org_id     uuid,                                  -- organization.id; null = platform-wide
    starts_at  timestamptz  not null,
    ends_at    timestamptz  not null,
    mode       varchar(10)  not null,                 -- ANNOUNCE | RESTRICT
    message    varchar(300) not null,
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        ,
    deleted_at timestamptz
);
create index idx_maintenance_active on maintenance_window (starts_at, ends_at)
    where deleted_at is null;
create index idx_maintenance_org on maintenance_window (org_id, starts_at desc)
    where deleted_at is null;
create index idx_maintenance_deleted on maintenance_window (deleted_at)
    where deleted_at is not null;

-- ---------------------------------------------------------------- V36__support.sql
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
create index idx_ticket_org on ticket (org_id, created_at desc, id desc) where deleted_at is null;
create index idx_ticket_status on ticket (status, priority, created_at desc) where deleted_at is null;
create index idx_ticket_sla on ticket (resolution_due_at)
    where deleted_at is null and escalated = false and status not in ('RESOLVED', 'CLOSED');
create index idx_ticket_deleted on ticket (deleted_at) where deleted_at is not null;
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

-- ---------------------------------------------------------------- V37__subscription_trial.sql
alter table org_subscription
    add column trial_ends_at timestamptz;   -- set while TRIALING; the instant the trial lapses
create index idx_org_subscription_trial_expiry
    on org_subscription (trial_ends_at)
    where status = 'TRIALING' and deleted_at is null;

-- ---------------------------------------------------------------- V38__org_sla_override.sql
create table org_sla_override
(
    id                     uuid        not null primary key,
    org_id                 uuid        not null,          -- organization.id; soft ref (no cross-module FK)
    priority               varchar(2)  not null,          -- P1 | P2 | P3 | P4
    first_response_minutes int         not null,
    resolution_minutes     int         not null,
    version                bigint      not null,
    created_at             timestamptz not null,
    created_by             uuid        ,
    updated_at             timestamptz,
    updated_by             uuid        ,
    constraint uq_org_sla_override unique (org_id, priority)
);

-- ---------------------------------------------------------------- V39__org_retention_override.sql
create table org_retention_override
(
    id             uuid        not null primary key,
    org_id         uuid        not null,          -- organization.id; soft ref (no cross-module FK)
    scope          varchar(40) not null,          -- RetentionScope: WEBHOOK_DELIVERY | EXCHANGE_JOB
    retention_days int         not null,
    version        bigint      not null,
    created_at     timestamptz not null,
    created_by     uuid        ,
    updated_at     timestamptz,
    updated_by     uuid        ,
    constraint uq_org_retention_override unique (org_id, scope)
);

-- ---------------------------------------------------------------- V40__payments.sql
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

-- ---------------------------------------------------------------- V43__payment_vat.sql
alter table payment add column vat_amount numeric(19,2);
alter table payment add column net_amount numeric(19,2);

-- ---------------------------------------------------------------- V45__feature_flag_targeting.sql
create table feature_flag_org_override (
    id         uuid        primary key,
    flag_key   varchar(150) not null,      -- soft ref to feature_flag.flag_key, and NOT a foreign key
                                           -- even though both tables are the settings module: the
                                           -- parent's uniqueness is a PARTIAL index over live rows
                                           -- (V17) and Postgres will not point an FK at one
    org_id     uuid        not null,       -- organization.id; soft ref, no cross-module FK
    enabled    boolean     not null,
    created_at timestamptz not null,
    unique (flag_key, org_id)              -- total, not partial: nothing here is soft-deletable, so a
                                           -- collision during a retarget fails hard and loudly
);

-- ---------------------------------------------------------------- V46__org_policy_require_mfa.sql
alter table org_security_policy add column require_mfa boolean not null default false;

-- ---------------------------------------------------------------- V47__geolocation.sql
create table geo_stamp (
    id                    uuid           primary key,
    org_id                uuid           not null,
    subject_type          varchar(64)    not null,
    subject_id            varchar(64)    not null,
    latitude              numeric(9, 6)  not null,
    longitude             numeric(9, 6)  not null,
    accuracy_m            numeric(10, 2),
    altitude_m            numeric(10, 2),
    source                varchar(16)    not null,
    captured_by_person_id uuid,                     -- null = unauthenticated OR machine capture
    captured_at           timestamptz    not null,
    consent_ref           varchar(64),              -- consent_record ref (V34), not a subject
    country_code          varchar(2),
    admin1                varchar(120),
    locality              varchar(160),
    formatted_address     text,
    place_id              varchar(128),
    geocoder_provider     varchar(24),
    created_at            timestamptz    not null,
    created_by            uuid        ,
    updated_at            timestamptz,
    updated_by            uuid        ,
    version               bigint         not null,
    deleted_at            timestamptz
);
create index geo_stamp_subject_idx  on geo_stamp (org_id, subject_type, subject_id) where deleted_at is null;
create index geo_stamp_captured_idx on geo_stamp (org_id, captured_at)              where deleted_at is null;
create index geo_stamp_bbox_idx     on geo_stamp (org_id, latitude, longitude)      where deleted_at is null;
create index geo_stamp_deleted      on geo_stamp (deleted_at)                        where deleted_at is not null;
create table geo_capture_policy (
    id                  uuid        primary key,
    org_id              uuid        not null,
    subject_type        varchar(64) not null,
    mode                varchar(16) not null,
    min_accuracy_m      numeric(10, 2),
    allowed_sources     varchar(64),
    max_fix_age_seconds integer,
    retention_days      integer,
    coarsen_after_days  integer,
    created_at          timestamptz not null,
    created_by          uuid        ,
    updated_at          timestamptz,
    updated_by          uuid        ,
    version             bigint      not null
);
create unique index uq_geo_capture_policy on geo_capture_policy (org_id, subject_type);

-- ---------------------------------------------------------------- V48__permission_vocabulary_areas.sql
insert into role_permission (role_id, permission)
select r.role_id, v.permission
from (select distinct role_id from role_permission where permission = 'ORG_READ') r
         cross join (values ('TICKET_READ'), ('TICKET_WRITE'),
                            ('EXCHANGE_READ'), ('EXCHANGE_SUBMIT'),
                            ('SEARCH_QUERY'), ('SUBSCRIPTION_READ'), ('USAGE_READ')) as v(permission)
on conflict do nothing;

-- ---------------------------------------------------------------- V49__hot_path_indexes.sql
drop index idx_ticket_status;
create index idx_ticket_status_recent on ticket (status, created_at desc, id desc)
    where deleted_at is null;                                    -- 5 buffers / 1.1 ms
create index idx_ticket_recent on ticket (created_at desc, id desc)
    where deleted_at is null;
create index idx_membership_org on membership (org_id);
create index idx_org_group_org_fk on org_group (org_id);
create index idx_webhook_delivery_org on webhook_delivery (org_id, created_at desc);
create index idx_audit_org_occurred on audit_log (org_id, occurred_at desc, id desc);
create index idx_audit_occurred_recent on audit_log (occurred_at desc, id desc);
drop index idx_audit_log_occurred;
drop index idx_webhook_sub_org;

-- ---------------------------------------------------------------- V50__hot_path_index_corrections.sql
drop index idx_exchange_job_claim;
create index idx_exchange_job_claim
    on exchange_job (created_at)
    where status in ('PENDING', 'VALIDATING', 'PROCESSING');
drop index idx_audit_created;
drop index idx_audit_org_created;

-- ---------------------------------------------------------------- V51__device_trust_per_org.sql
create table user_device_trust
(
    device_id            uuid        not null,        -- user_device.id — SOFT ref, no FK: platform tier
    org_id               uuid        not null,        -- organization.id; soft ref, no cross-module FK
    granted_at           timestamptz not null,
    granted_by_person_id uuid,                        -- person.id; soft ref, nullable for system grants
    primary key (device_id, org_id)
);
create index idx_user_device_trust_org on user_device_trust (org_id);

-- ---------------------------------------------------------------- V52__index_corrections.sql
create index idx_org_group_role_fk on org_group (role_id);
alter table payment drop constraint payment_merchant_reference_key;

-- ---------------------------------------------------------------- V53__tenant_boundary_soft_refs.sql
alter table user_device_trust
    add column person_id   uuid,
    add column fingerprint varchar(100);
alter table user_device_trust
    alter column person_id set not null,
    alter column fingerprint set not null;
create index idx_user_device_trust_enforcement on user_device_trust (org_id, person_id, fingerprint);

-- Back to the platform path before Flyway writes its history row. Belt and braces — the row is
-- written against the fully-qualified `platform.flyway_schema_history` — but a migration that
-- leaves the connection pointing somewhere else is a trap for whatever runs next.
set local search_path to platform;
