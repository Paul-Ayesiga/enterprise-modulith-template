-- Search: a module-local projection queried with Postgres FTS — no new engine, honest speed (the
-- gate test measures p95 over 100k rows). NOT soft-deletable, deliberately: a projection is
-- rebuildable from its sources, so deletion here is un-indexing, not the loss of a record. The tsv
-- column is GENERATED so producers can never write a vector that disagrees with the text.
-- pg_trgm needs superuser/extension privilege once per database (the dev/test containers have it;
-- grant it in production provisioning, not at app runtime).
--
-- entity_id IS POLYMORPHIC and stays a varchar: it holds whatever key entity_type names — person.id
-- on 'user' rows, organization.id on 'organization' rows, a document id elsewhere — so typing it to
-- any one of those would be wrong for all the others. It is called out here because the name does
-- not contain "subject", so a name-driven sweep for identity columns misses it entirely while the
-- rows are as identity-bearing as any. Re-keying is the PRODUCER's job, one entity_type at a time.
-- And because this is a projection, that migration is a REBUILD — drop the rows, re-emit them from
-- the sources — never an UPDATE: uq_search_entity below only makes a rekey collision-free if the old
-- rows go first. The rebuild is also the only way to refresh title/body, which carry a copy of the
-- indexed row's own text (a person's email among it) that no column rename would touch.

create extension if not exists pg_trgm;

create table search_document
(
    id          uuid primary key,
    org_id      uuid,                        -- organization.id; null = platform-wide (admin search only)
    entity_type varchar(40)  not null,       -- the discriminator entity_id is read through
    entity_id   varchar(64)  not null,       -- polymorphic key; never converted to a typed id
    title       varchar(300) not null,
    body        text         not null,
    tsv         tsvector generated always as (to_tsvector('simple', title || ' ' || body)) stored,
    updated_at  timestamptz  not null,
    constraint uq_search_entity unique (entity_type, entity_id)
);

-- The match itself.
create index idx_search_tsv on search_document using gin (tsv);

-- Prefix/typo fallback over titles when the tsquery finds nothing.
create index idx_search_title_trgm on search_document using gin (title gin_trgm_ops);

-- The tenant cut applied inside every org-scoped query.
create index idx_search_org on search_document (org_id);
