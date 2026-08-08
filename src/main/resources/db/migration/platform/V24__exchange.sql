-- Exchange: the import/export platform's job table — a durable work queue, not an aggregate, so it
-- follows the delivery-queue species: no entity (plain JDBC), fenced status updates, stale-lock
-- reclaim, and NOT soft-deletable (a job log is trimmed by retention when that lands, like the
-- delivery logs). The scale decisions live in the columns: next_offset makes a reclaimed job RESUME
-- where the crashed claimant's last committed batch ended instead of restarting a 100k-row file,
-- attempts is the claim generation every fenced update checks, and cancel_requested lets a running
-- job stop at the next batch boundary without anyone yanking its row.
--
-- SPLIT (ADR 0010 §4.1): this is the platform half of V24. Its sibling is db/migration/tenant/V24__exchange.sql.

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

-- The claim: eligible work only (partial keeps the index tight as terminal rows pile up).
create index idx_exchange_job_claim on exchange_job (status, created_at)
    where status in ('PENDING', 'VALIDATING', 'PROCESSING');

-- Tenant listing, house keyset sort.
create index idx_exchange_job_org_recent on exchange_job (org_id, created_at desc, id desc);

-- Future retention purge scans terminal rows by age (mirrors the delivery logs).
create index idx_exchange_job_terminal on exchange_job (created_at)
    where status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED');

-- Row errors are DURABLE, not accumulated in worker memory: they commit with the batch that found
-- them, so a crash loses none and the finalize step streams the COMPLETE report from here. The PK
-- makes replayed batches idempotent (on conflict do nothing) — at-least-once processing writes each
-- row's error exactly once.
create table exchange_job_error
(
    job_id  uuid          not null references exchange_job (id) on delete cascade,
    row_num bigint        not null,
    error   varchar(500)  not null,
    primary key (job_id, row_num)
);
