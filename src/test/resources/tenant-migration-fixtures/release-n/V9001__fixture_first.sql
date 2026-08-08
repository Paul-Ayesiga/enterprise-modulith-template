-- TEST FIXTURE, NOT A MIGRATION. It lives outside src/main/resources/db/migration on purpose and takes
-- no number from AGENTS §4.5's single global counter — 9001 is deliberately outside the range a real
-- migration will ever reach, the way V9999 was, so nobody reading `ls` mistakes it for one.
--
-- WHAT IT IS FOR. TenantMigrationRunnerTest needs the shape ADR 0010 §7 Phase 4's gate describes: a
-- tenant migration that fails on ONE schema, leaving it at the PREVIOUS version with no history row.
-- "Previous version" means the schema must already be at one, so the fixture is two releases rather
-- than one file: this directory is release N (V9001 alone) and ../release-n1 is release N+1, which adds
-- the migration that fails. Writing a failing migration into db/migration/tenant would fail every
-- deploy forever; the runner takes its script set as a constructor parameter so it does not have to.
--
-- ../release-n1/V9001__fixture_first.sql IS A BYTE-FOR-BYTE COPY OF THIS FILE and has to stay one:
-- release N+1 re-resolves every migration already in the schema's history and validates its checksum,
-- so a drift between the two would fail both schemas for the wrong reason and the test would still be
-- red — just about something else. MigrationScriptsTest asserts they are identical so the message says
-- so.
create table fixture_first
(
    id int primary key
);

insert into fixture_first (id)
values (1);
