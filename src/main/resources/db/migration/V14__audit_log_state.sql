-- Enrich the audit trail to a full who/when/where/what/from→to record. `detail` is superseded by the
-- structured before/after columns. (audit_log is one release old and event-only; no data to preserve.)
alter table audit_log drop column detail;
alter table audit_log add column from_state varchar(1000);
alter table audit_log add column to_state   varchar(1000);
