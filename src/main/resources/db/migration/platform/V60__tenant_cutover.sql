-- ADR 0011 §7.2 step 2: a cutover — one tenant's silo moving to another DATABASE — recorded as a
-- queryable fact for its whole life, from the first byte replicated to the day the stale source copy
-- is dropped.
--
-- WHY A TABLE, AGAIN. The V57 doctrine verbatim: partial-fleet state is a `select`, not an incident.
-- A cutover is the first operation here that spans DAYS (the P7D watch window) and survives the
-- process that started it, so "is this tenant mid-move, and how far" must be answerable at 03:00
-- without the operator who ran it. It is also read as a REFUSAL by two other subsystems, which is why
-- it cannot be an in-memory state: the tenant-migration runner declines to migrate a schema with a
-- live row here (ADR 0011 §7.3 — DDL under a live publication crash-loops the subscriber's apply
-- worker every ~5 s, and healing the stream by hand then SILENTLY SKIPS the new table's rows until
-- ALTER SUBSCRIPTION … REFRESH PUBLICATION; both failure modes were measured, not read about), and
-- the promoter declines to move a tenant a cutover holds (TenantPlacements.beginRelocation).
--
-- WHY IT IS NOT MORE tenant_placement COLUMNS. The placement answers WHERE a tenant is served from
-- and is read on the request path; a cutover is a days-long operation ABOUT that row, read by jobs
-- and operators. Same split as V58's freeze-vs-placement argument: folding them together would put an
-- operation's transient state in the row every connection borrow consults.
--
-- ONE LIVE CUTOVER PER TENANT, enforced by the primary key. There is no terminal state: a finished
-- cutover's row is DELETED (by decommission, rollback or abort), so the table is the set of moves in
-- flight — the same shape as tenant_freeze, and like it usually empty.

create table tenant_cutover
(
    -- `organization.id`. SOFT REF, NO FK — V57's three reasons apply verbatim, and a fourth: this row
    -- must survive anything that happens to the organization mid-move, because it is the only record
    -- that replication objects (a publication, two subscriptions, their slots) exist on TWO databases
    -- and must be torn down. An orphaned slot pins WAL forever (ADR 0011 §7.2 step 10).
    org_id            uuid        not null primary key,

    -- The silo being moved. Derivable from org_id (TenantSchemas.siloSchema) but recorded so the two
    -- refusals above are one indexed lookup BY SCHEMA NAME from code that holds no org id — the
    -- migration runner walks schemas, not tenants. Also what the operator's `select` joins on.
    schema_name       text        not null,

    -- Where the tenant is served from at the start of the move, and where a rollback puts it back.
    -- 'primary' for every hop 1→2 cutover; the remote name when a reverse cutover brings one home.
    -- Recorded rather than re-read because the placement's datasource_name CHANGES at the flip, and
    -- the rollback needs the pre-flip answer after that.
    source_datasource text        not null,

    -- The datasource the tenant is moving to — what the flip writes into placement.datasource_name.
    target_datasource text        not null,

    -- SYNCING  — destination built, publication + subscription live, initial copy or catch-up in
    --            progress. The tenant is fully served from the source; nothing about serving has
    --            changed. Abortable by dropping the streams and this row.
    -- CUT      — the placement flip has committed: the tenant is served from the target. Reverse
    --            replication is not yet confirmed up, so this is the one state a crash leaves that
    --            needs an operator's judgement (docs/runbooks/tenant-cutover.md, "stuck at CUT").
    -- WATCHING — reverse replication is streaming target → source, so flipping back loses nothing.
    --            Rollback stays this cheap until decommission deletes the row.
    state             text        not null,

    started_at        timestamptz not null default now(),

    -- When the flip committed; null while SYNCING. `cut_at + watch window` is what decommission
    -- refuses before — the reverse stream must have insured the move for the full window first.
    cut_at            timestamptz,

    updated_at        timestamptz not null default now(),

    -- The V57 argument at the same column: a typo'd state would read as "not SYNCING" to the code
    -- that resumes and as "not WATCHING" to the code that decommissions — held forever, refused by
    -- everything, reported by nothing.
    constraint tenant_cutover_state_known
        check (state in ('SYNCING', 'CUT', 'WATCHING')),

    -- A move to where the tenant already is would flip nothing and tear down streams it never built.
    constraint tenant_cutover_actually_moves
        check (source_datasource <> target_datasource)
);

-- The migration runner's refusal is a lookup by schema name on every fan-out visit; the table is
-- near-empty so this is cheap insurance rather than a hot-path index.
create index idx_tenant_cutover_schema on tenant_cutover (schema_name);
