-- Completes the exchange platform against its guidelines doc: template VERSIONING (which shape of
-- a handler's file a job was submitted against — mismatches then say which version to re-download)
-- and SCHEDULED recurring exchanges. Schedules are exports only, by design: a recurring import has
-- no source to read; a recurring export regenerates from live data every run.
--
-- SPLIT (ADR 0010 §4.1): this is the platform half of V25. Its sibling is db/migration/tenant/V25__exchange_platform_completion.sql.

alter table exchange_job
    add column handler_version int not null default 1;

-- V24 left org_id nullable for a "platform-scoped" job species nothing submits and the worker's
-- MDC/find paths never handled — a null here today is a bug, not a feature. Tighten until a real
-- platform-job design (with its own authorization story) earns the relaxation back.
alter table exchange_job
    alter column org_id set not null;
