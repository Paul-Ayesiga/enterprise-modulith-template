-- ADR 0010 Phase 1: the two schemas the routing DataSource needs, and pg_trgm moved out of the way.
-- No table moves here. Phase 1 installs the mechanism while every tenant state still resolves to the
-- schema the 55 tables already occupy; Phase 2 is what splits them.

-- `ext` exists so extension-provided operators stay reachable from EVERY tenant's search_path without
-- putting a data-bearing schema on it. That is the property the whole design rests on: a tenant's path
-- is `<its own schema>, ext`, and `ext` holds functions and operator classes only — never a row. Search
-- falls through to it and finds no other tenant's data there, because there is none to find.
create schema if not exists ext;

-- The poison schema. A connection borrowed with no tenant declared gets `search_path TO no_tenant`, and
-- because this schema is EMPTY every unqualified tenant table resolves to nothing:
--   ERROR: relation "ticket" does not exist
-- That is deliberately ugly and deliberately loud. The alternative — leaving the connection on whatever
-- path the previous borrower set — is the failure this design exists to make impossible, and it fails
-- SILENTLY by serving another tenant's rows. An empty schema converts that into a stack trace.
-- It must stay empty. Nothing is ever created here.
create schema if not exists no_tenant;

-- AND IT MUST STAY EMPTY, so take away the ability to fill it rather than trusting nobody will.
-- `search_path TO no_tenant` makes an unqualified CREATE land HERE, which turns any framework that
-- initializes its own schema — Spring Modulith's event registry, ShedLock, a stray ddl-auto — from a
-- loud failure into a silent one: it succeeds, the table exists in a schema nothing else can see, and
-- the application works until the row someone needs is in the copy nobody reads. `event_publication`
-- did exactly that and was caught only by TenancyTierBoundaryTest sweeping for strays.
--
-- Revoking CREATE moves that discovery to the moment it happens, with the creator's own stack trace.
-- The schema stays readable and resolvable, so the fail-closed behaviour it exists for is unchanged:
-- a tenant-less borrow still resolves here and still finds no tables.
revoke create on schema no_tenant from public;

-- pg_trgm is relocatable (verified: pg_extension.extrelocatable = true) and moves whole — its operators,
-- its functions, and the `gin_trgm_ops` operator class the search index is built on all travel with it.
--
-- WHY MOVE IT AT ALL: after Phase 2, `public` holds nothing and is not on any search_path. An extension
-- left there would take `word_similarity()` and the `%` operator with it, and SearchQueryService uses
-- both in native SQL (`word_similarity(?, d.title)`, and `%` in the WHERE clause). The failure would not
-- be a missing function so much as a missing INDEX: V22 builds `idx_search_title_trgm` using
-- `gin (title gin_trgm_ops)`, and an operator class that cannot be resolved makes the index unusable
-- for the query it exists to serve — a silent fall back to a sequential scan over every document.
--
-- VERIFIED AFTER RELOCATION, against 200k seeded search_document rows with `search_path = <schema>, ext`:
--   word_similarity('Tick','Ticket 42') -> 0.8            (function resolves)
--   title % 'Ticket'                    -> 200000 rows    (operator resolves)
--   selective probe                     -> Bitmap Index Scan on idx_search_title_trgm,
--                                          29 buffers, 0.135 ms   (the index still serves it)
-- The index did NOT need rebuilding: an operator class is referenced by oid, not by name, so moving its
-- schema does not invalidate an index already built on it. What moving it changes is only whether a
-- QUERY can name the operator — hence `ext` on every path.
alter extension pg_trgm set schema ext;
