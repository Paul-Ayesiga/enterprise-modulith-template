-- Completes the exchange platform against its guidelines doc: template VERSIONING (which shape of
-- a handler's file a job was submitted against — mismatches then say which version to re-download)
-- and SCHEDULED recurring exchanges. Schedules are exports only, by design: a recurring import has
-- no source to read; a recurring export regenerates from live data every run.

alter table exchange_job
    add column handler_version int not null default 1;

-- V24 left org_id nullable for a "platform-scoped" job species nothing submits and the worker's
-- MDC/find paths never handled — a null here today is a bug, not a feature. Tighten until a real
-- platform-job design (with its own authorization story) earns the relaxation back.
alter table exchange_job
    alter column org_id set not null;

-- User-managed configuration, so it follows webhook_subscription's species: a soft-deletable
-- aggregate (the ELEVENTH... tenth is org_subscription in V26) with optimistic locking.
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

-- The firing scan: due, enabled, live rows only — everything else is dead weight to the poller.
create index idx_exchange_schedule_due on exchange_schedule (next_run_at)
    where enabled and deleted_at is null;

-- Org listing (keyset createdAt desc, id desc).
create index idx_exchange_schedule_org on exchange_schedule (org_id, created_at desc, id desc)
    where deleted_at is null;

-- Retention purge scan over soft-deleted rows (V17 pattern).
create index idx_exchange_schedule_deleted on exchange_schedule (deleted_at)
    where deleted_at is not null;
