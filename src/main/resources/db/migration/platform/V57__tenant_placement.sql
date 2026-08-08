-- ADR 0010 Phase 4 §4.2: the placement registry — where each tenant's rows live, and whether the
-- schema that holds them is fit to serve.
--
-- WHY A TABLE AND NOT A LOG LINE. §4.2 states the requirement in one sentence: "partial-fleet state
-- must be a queryable fact, not an incident". Per-tenant migration is the first thing this system
-- does that can succeed for 4,997 tenants and fail for three, and the runner is forbidden from
-- aborting on a failure (§4.2, and `SoftDeletePurgeJob`'s "loud AND complete, not loud instead of
-- complete"). A fan-out that continues past failures MUST leave the failures somewhere they can be
-- read back with a `select`, or "which tenants are behind?" is answered by grepping the logs of a pod
-- that has since been replaced. Every column below exists because some question has to be answerable
-- at 03:00 without a deploy.
--
-- WHAT IT IS NOT. It is not the tenant list — `platform.organization` is. This table answers a
-- narrower question about each tenant: WHICH SCHEMA, on WHICH DATASOURCE, in WHAT CONDITION. Those
-- three are the coordinates of ADR 0010's whole journey — Phase 5 changes the first, Phase 7 the
-- second, and the third is what says a tenant may be served at all in between.

create table tenant_placement
(
    -- `organization.id`, and the primary key rather than a column with an index: one tenant has
    -- exactly one home, and the request path reads this row by org id on the way to deciding whether
    -- to serve (§4.4's floor check). A surrogate key would buy nothing and would admit two rows.
    --
    -- SOFT REF, NO FK, and both of AGENTS §1's clauses ask for it independently. The module clause:
    -- `organization` belongs to `organization/`, this table to `shared/`, and a key between them is a
    -- cross-module key. The tier clause happens to permit it — both are platform-tier — which is
    -- exactly the case §1's note warns about, where one clause passes and the other does not.
    --
    -- And a third reason that outlives both: this row has to be able to survive its organization. A
    -- tenant hard-deleted (retention purge) still has bytes in a schema that somebody must reclaim,
    -- and `on delete cascade` would delete the only record of where they are.
    org_id          uuid        not null primary key,

    -- `tenant_pool` while the tenant is pooled, `t_<32hex>` once promoted (`TenantSchemas`). The
    -- NAME, not a boolean "is siloed": Phase 6 restores a dump into a schema whose name is a fact
    -- about that dump, and a boolean would have to be re-derived into a name at every use.
    schema_name     text        not null,

    -- Always 'primary' until Phase 7, where `AbstractRoutingDataSource` starts keying on it (§6 hop
    -- 1→2). Written now, at not-null with a default, because the column is the cheap half of that hop
    -- and adding it later is a migration against a table the request path reads. Expand-shaped: the
    -- current binary never reads it, and the value it would read is correct.
    datasource_name text        not null default 'primary',

    -- PROVISIONING → ACTIVE, or PROVISIONING → FAILED. See the check constraint below for what each
    -- one licenses; the short version is that only ACTIVE means "this tenant may be served".
    state           text        not null,

    -- The tenant sequence's head as last APPLIED to `schema_name` — the version §4.4's floor check
    -- compares `MIN_TENANT_SCHEMA_VERSION` against, so a binary never selects a column the schema it
    -- is about to query does not have.
    --
    -- IT IS A PROPERTY OF THE SCHEMA, DENORMALIZED ONTO EVERY TENANT THAT LIVES THERE. Five thousand
    -- pooled tenants share `tenant_pool` and therefore share one version, and when the pool moves,
    -- one `update … where schema_name = 'tenant_pool'` moves all five thousand rows. The alternative
    -- — a `tenant_schema` table joined on every request — would put a join on the hot path to avoid a
    -- single statement that runs once per release. Denormalize toward the reader.
    --
    -- NULL MEANS "NOT YET RECORDED", NEVER "BEHIND". Nothing writes this until a migration runner
    -- applies the tenant sequence to that schema and says so, and today nothing does: `tenant_pool`
    -- is still built by the `db/migration/bootstrap/V9999__tenant_pool.sql` scaffold from inside the
    -- platform run, which keeps no history of its own. So every row this migration backfills has NULL
    -- here, and a floor check that read NULL as "below the floor" would 503 the entire fleet on the
    -- deploy that introduced it. Unknown is unknown: serve, and let the runner make it known.
    schema_version  text,

    -- Why the last provisioning or migration attempt failed, verbatim, and NULL on every success.
    -- This is the column that makes a FAILED row worth having: a state with no reason sends whoever
    -- reads it back to the logs, which is the thing this table exists to stop being necessary.
    last_error      text,

    -- When the state last changed. Not `created_at`/`updated_at`: nothing here cares when a placement
    -- was first recorded, and everything cares how long a tenant has been stuck — which is
    -- `now() - updated_at` on a FAILED or PROVISIONING row, and is what an alert is written against.
    updated_at      timestamptz not null default now(),

    -- THREE STATES, AND THE CONSTRAINT IS HOW A TYPO STAYS OUT OF THE ONE TABLE THAT DECIDES WHETHER
    -- A TENANT IS SERVED. A misspelt state on a text column reads as "not ACTIVE" to every query that
    -- asks the safe question and as "not FAILED" to every query that asks the loud one, so it would
    -- be a tenant that is both refused and unreported.
    --
    --   PROVISIONING — a home has been claimed for this tenant and is not yet proven fit. Set BEFORE
    --                  any DDL runs, so a process killed mid-provision leaves this rather than
    --                  nothing. It is also the state that says NOT YET ANNOUNCED (see below).
    --   ACTIVE       — the schema exists, is at the recorded version, and the tenant has been
    --                  announced. The only state that licenses serving a request.
    --   FAILED       — provisioning or migration attempted and did not finish. `last_error` says why.
    --                  The tenant's schema is at whatever version it reached; nothing was left half
    --                  applied, because tenant migrations run inside a transaction (§4.2 forbids
    --                  `executeInTransaction=false` for exactly this).
    --
    -- ADDING A FOURTH (Phase 5's PROMOTING is the candidate) IS AN EXPAND STEP AND MUST SHIP FIRST,
    -- one release ahead of the code that writes it — AGENTS §4.6. A check constraint widened in the
    -- same release as its first writer breaks the moment a rolling deploy puts the new binary in
    -- front of the old schema.
    constraint tenant_placement_state_known
        check (state in ('PROVISIONING', 'ACTIVE', 'FAILED'))
);

-- Serves the two statements that address the fleet by SCHEMA rather than by tenant: the runner's
-- `update … set schema_version = ? where schema_name = ?` after it migrates a schema, and Phase 5's
-- bounded fan-out, which needs the distinct schema list and must never scan five thousand rows to
-- find the two hundred silos.
create index idx_tenant_placement_schema on tenant_placement (schema_name);

-- "WHICH TENANTS ARE NOT FIT TO SERVE, OLDEST FIRST" — the alert query, and the reason this table
-- was specified as a registry instead of a log. Partial, and the predicate is what makes it worth
-- having: ACTIVE is the overwhelming majority of a healthy fleet, so a full index here would be five
-- thousand dead entries maintained on every write to carry a query whose correct answer is usually
-- zero rows. `updated_at` trails the state so "stuck for more than an hour" is a range scan and not a
-- sort.
create index idx_tenant_placement_unhealthy on tenant_placement (state, updated_at)
    where state <> 'ACTIVE';

-- BACKFILL: every organization that already exists is already served, so it is already ACTIVE and
-- already pooled. Without this the registry would describe only tenants created after this migration,
-- and a fleet-wide question ("is every tenant at the current version?") would answer confidently from
-- a table that had never heard of the five thousand tenants that matter.
--
-- Soft-deleted organizations included, deliberately: their rows are still in `tenant_pool` and are
-- restorable until the purge, so a placement that skipped them would make a restore land on a tenant
-- with no recorded home. `deleted_at` is not consulted anywhere below.
--
-- `schema_version` is left NULL rather than guessed — see the column's own note. The one honest value
-- would come from `tenant_pool`'s own Flyway history, and that table does not exist yet: the pool is
-- still replayed from inside the platform run by the V9999 scaffold. When Phase 4's runner takes that
-- over, its first successful pass over `tenant_pool` fills every one of these rows with one statement.
--
-- `on conflict do nothing` so a re-run is a no-op. Nothing else writes this table before this
-- statement, so the clause is insurance rather than logic — but a backfill that can only be run once
-- is a backfill that cannot be re-run after a partial restore.
insert into tenant_placement (org_id, schema_name, datasource_name, state, schema_version, updated_at)
select o.id, 'tenant_pool', 'primary', 'ACTIVE', null::text, now()
  from organization o
on conflict (org_id) do nothing;
