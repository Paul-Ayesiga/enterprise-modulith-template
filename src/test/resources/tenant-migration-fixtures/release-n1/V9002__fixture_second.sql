-- TEST FIXTURE, NOT A MIGRATION — see V9001__fixture_first.sql's header for why 9001/9002 take no
-- number from the global counter.
--
-- THE FAILURE IS ENVIRONMENTAL, NOT SYNTACTIC, AND THAT IS THE POINT. This file is valid SQL and
-- succeeds in a schema that release N left alone. It fails in a schema that already holds
-- `fixture_wedge`, which the test creates in exactly one of the two schemas it hands the runner —
-- AFTER release N has run, so the schema has a flyway_schema_history and Flyway does not refuse it as
-- an unbaselined non-empty schema before reaching this file at all. So the same script set is applied
-- to both schemas, one succeeds and one fails, and "the runner did not stop" is a claim about the
-- schema that came AFTER the failure rather than about a different set of scripts. Real tenant
-- migrations fail this way too — on what one silo happens to hold, never on syntax, which the build
-- would have caught.
--
-- THE ORDER OF THE THREE STATEMENTS IS THE ASSERTION. The first two succeed before the third fails, so
-- a schema left holding `fixture_boom` — or the row in it — would prove the migration was NOT atomic.
-- It is: Postgres has transactional DDL, Flyway runs an SQL migration and its own
-- flyway_schema_history insert in one transaction, and ADR 0010 §4.2 forbids
-- executeInTransaction=false in the tenant sequence precisely so this stays true. The test asserts
-- fixture_boom is absent afterwards, and that the schema is still at 9001 with no 9002 history row.
create table fixture_boom
(
    id int primary key
);

insert into fixture_boom (id)
values (1);

create table fixture_wedge
(
    id int primary key
);
