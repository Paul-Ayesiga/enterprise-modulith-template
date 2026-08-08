-- ADR 0010 Phase 5, §6 hop 0->1: the half of a promotion freeze that HTTP cannot express.
--
-- WHY A SECOND FREEZE EXISTS AT ALL. §6 states the hazard in one sentence and it is the sentence this
-- table is for: "the freeze window is an org-scoped `maintenance_window` row in RESTRICT mode — BUT
-- THAT GATES HTTP ORG PATHS ONLY; the notification worker, webhook dispatcher, exchange runners,
-- OutboxResubmissionJob and retention purges all write tenant rows OUTSIDE any request and must be
-- paused for that org too." A promoter that took only the HTTP freeze would run
-- `insert into t_<hex>.ticket … select … from tenant_pool.ticket` while `SlaEscalationJob` was still
-- updating the rows it was reading, and the verification step afterwards would pass or fail on timing
-- — which is the worst possible shape for a check whose whole job is to be conclusive.
--
-- WHY IT IS NOT A FOURTH `tenant_placement.state`. Phase 5's PROMOTING was the obvious candidate and
-- V57's own header rules it out for this release: widening `tenant_placement_state_known` is an EXPAND
-- step that must ship one release ahead of the first code that writes the new value (AGENTS §4.6),
-- because a rolling deploy puts the new binary in front of the old schema. A new table has no such
-- ordering problem — the previous binary simply never selects from it. Two further reasons make the
-- separate table the better shape even without that constraint:
--
--   * A FREEZE EXPIRES AND A PLACEMENT DOES NOT. A promoter killed between `freeze` and `thaw` must
--     not pause a tenant's background work forever, so the freeze carries its own deadline and every
--     reader compares against it. A state column has nowhere to put that, and "PROMOTING since 04:12"
--     would need a second column and a sweeper to mean anything.
--   * A PLACEMENT ANSWERS "WHERE", A FREEZE ANSWERS "MAY ANYONE WRITE". They are read by different
--     code on different paths — the router and the version floor read placement on the request path;
--     the queue claim and the per-tenant jobs read this one — and folding them together would put a
--     promotion's transient state in the row the request path reads on every call.
--
-- WHAT IT DOES NOT DO. It does not stop the promoter itself: every statement the promoter issues is
-- schema-qualified and goes nowhere near this table. It does not stop HTTP writes either — that is
-- `maintenance_window`'s job, through `MaintenanceFilter` at @Order(4), and the promoter opens both.
-- And it is not a lock: two promotions of the SAME tenant are refused by the placement registry's
-- conditional writes, not here.

create table tenant_freeze
(
    -- `organization.id`, and the primary key: a tenant is frozen or it is not, and a second row would
    -- be a second answer. SOFT REF, NO FK, for the reason V57 gives at the same column — the module
    -- clause of AGENTS §1 (`organization` belongs to `organization/`, this table to `shared/`), plus
    -- the operational one: a freeze must be removable after its organization has been hard-deleted,
    -- and `on delete cascade` would silently thaw a tenant mid-copy.
    org_id     uuid        not null primary key,

    -- Free text, and it is read by a human at 03:00 rather than parsed: "promotion of <org> into
    -- t_<hex> by <operator>". A frozen tenant with no stated reason sends whoever finds it to the
    -- logs, which is the thing every registry in this schema exists to stop being necessary.
    reason     text        not null,

    -- When the freeze was taken, on the DATABASE's clock. Every other timestamp here is compared
    -- against `now()` in SQL, so minting this one from the JVM would put app/database skew between
    -- the row and the comparison that reads it.
    frozen_at  timestamptz not null default now(),

    -- THE DEADLINE, AND IT IS THE MOST IMPORTANT COLUMN HERE. A reader treats a row whose
    -- `expires_at` has passed as no freeze at all, so a promoter that dies mid-run stops the tenant's
    -- background work for a bounded window instead of forever. It is set from the configured freeze
    -- budget (`app.tenancy.promotion.freeze`), which is also what the runbook tells the operator to
    -- size against the tenant's row count.
    --
    -- The promoter re-asserts the freeze rather than extending it silently: a copy that outruns its
    -- own deadline is a promotion that should be aborted and re-planned at a bigger budget, not one
    -- that quietly keeps a fleet's workers idle.
    expires_at timestamptz not null,

    -- WHICH process took it: the pod's identity, so "who froze this tenant and is that process still
    -- alive" is answerable. Not a lease token — this table arbitrates nothing, because the thing that
    -- makes two concurrent promotions impossible is `tenant_placement`'s conditional writes.
    holder     text        not null,

    -- A freeze that expires before it begins is not a freeze; it is a typo that reads as "not frozen"
    -- to every consumer and would let a promotion copy rows with nothing paused at all.
    constraint tenant_freeze_ends_after_it_starts check (expires_at > frozen_at)
);

-- NO INDEX, DELIBERATELY. The whole table is the set of promotions happening right now, which is one
-- row on the busiest day this design anticipates (§8 Q3: operator-initiated only). Every read is by
-- primary key; the only scan is "which freezes are stale", over a table Postgres will read as a
-- single page whatever we build on it. An index here would be maintained on every write to carry a
-- query that has nothing to seek through.
