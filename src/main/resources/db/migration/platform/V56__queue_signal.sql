-- ADR 0010 Phase 3 §2.1: the queue signal — one row per (queue, tenant) that has unfinished work.
--
-- WHAT IT REPLACES. All three durable queues discovered work the same way: one cluster-wide
-- `for update skip locked` claim over the whole table, ordered oldest-first. That shape has three
-- problems and this table fixes all three at once.
--
--   1. IT CANNOT SURVIVE THE SPLIT. `webhook_delivery` and `webhook_subscription` are tenant-tier, so
--      `WebhookDeliveryQueue.claim`'s join between them is only expressible while a tenant is pinned —
--      and a claim is a search for work that belongs to no tenant yet, so nothing could pin it. Naming
--      the schema is not an answer either: a promoted tenant's schema is `t_<32hex>`, which no
--      compiled-in constant knows. Claiming the TENANT first and the ROWS second is the only order in
--      which the pin can exist before the statement that needs it. `exchange_job` had the same problem
--      and paid for it with a scan per home (`SplitTables.homes()`); that cost is what this removes.
--
--   2. DISCOVERY WAS O(TENANTS). An empty per-tenant sweep over 5,000 schemas costs 0.7–1.4 s
--      server-side against a 1 s notification poll (ADR 0010 §1). With this table the poller reads the
--      tenants that are actually DUE and nothing else: a tenant with no queued work has no row here at
--      all, so visiting it costs nothing because it is never visited.
--
--   3. FAIRNESS WAS A PREDICATE SOMEBODY HAD TO REMEMBER. This is the one that already cost an outage.
--      `WebhookDeliveryQueue.claim`'s javadoc narrates it: one paused subscription's rows occupied every
--      slot of a single global `limit`, every poll claimed zero, and NO tenant got a webhook until
--      somebody noticed. The fix at the time was to push the liveness predicates inside the limit —
--      correct, and still there, and still a thing a future edit can undo. One signal row per tenant
--      makes the general case STRUCTURAL instead: a claim covers exactly one tenant, so one tenant can
--      hold at most one claim slot and a tenant with a million queued rows cannot occupy a second
--      worker's batch. That is a guarantee about the SHAPE of the claim rather than about a WHERE
--      clause, which is the difference between a fix and an invariant.
--
-- THE PRIMARY KEY IS THE POINT. `(queue, org_id)` — one row per tenant per queue, never one per
-- message. Enqueue upserts once per BATCH; a fan-out of 40,000 deliveries writes one signal row, not
-- 40,000. This is an index over the queue, not a second copy of it, and it stays small enough to sort
-- by `due_at` on every poll precisely because it counts TENANTS.
create table queue_signal
(
    queue  text        not null,
    org_id uuid        not null,
    due_at timestamptz not null,
    lease  uuid,
    primary key (queue, org_id)
);

-- `lease` IS THE FENCE, AND IT IS A COLUMN OF ITS OWN BECAUSE `due_at` CANNOT BE ONE. A claim hands a
-- tenant to a worker that then goes away for a whole batch on another connection, so the release that
-- follows has to prove it is still the holder — otherwise a worker whose lease expired mid-batch would
-- overwrite the due_at of whoever claimed the tenant after it, and an enqueue that pulled a tenant
-- forward during the batch would be pushed back behind work that is already waiting.
--
-- The obvious cheap fence is "release only where due_at is still the value the claim stamped", and it
-- was tried. It couples two things that must not be coupled:
--
--   * the token would be a TIMESTAMP, so proving identity would mean comparing timestamps for exact
--     equality across a JDBC round trip — a silent, total loss of fairness the first time a driver,
--     a session TimeZone, or a microsecond rounding step disagrees with itself. Nothing raises; the
--     release simply matches no row, and the tenant it should have moved to the back of the queue is
--     claimed again on the next poll, forever.
--   * and it would force the LEASE EXPIRY to be minted where the token is minted. Mint it in the app
--     and `due_at <= now()` — the DATABASE's clock — is being compared against a value from the JVM's,
--     so every millisecond of skew silently lengthens or shortens every lease in the fleet.
--
-- A uuid the claim generates settles both. Identity is a uuid comparison, which no round trip can
-- round; `due_at` goes back to being nothing but "when this tenant is claimable again", minted by
-- `now() + lease` on the same clock that reads it. The two questions the row answers — WHEN is it
-- claimable, and WHO holds it — stop being one column pretending to answer both.
--
-- Nullable, and null is the ordinary state: a tenant nobody is working on holds no lease, `release`
-- clears it on the way out, and `raise` clears it too — an enqueue voids the outstanding lease so the
-- release that lands after it can only be a no-op, which is what keeps freshly queued work from being
-- buried under a due_at computed before it existed.

-- `org_id` IS NOT NULL AND THE ORG-LESS WORK STILL NEEDS A ROW. Two of the three queues carry rows
-- that belong to no organization — `notification_delivery.org_id` is genuinely nullable (V41 added the
-- column late) and `exchange_job.org_id` null means a platform-scoped handler (V24:11). They key on the
-- nil uuid, `00000000-0000-0000-0000-000000000000`, which no `organization.id` can ever be: every
-- primary key in this schema is a random or time-ordered uuid and none of them is all zeroes. The
-- constant is `QueueSignals.PLATFORM_SCOPE`, and it is NOT the same idea as the `new UUID(0L, 0L)` that
-- jobs and workers pass to `TenantContext` — that one names an AXIS ("an org in no `organization` row
-- can only resolve to the pool"), this one names a ROW SCOPE ("the rows whose org_id is null"). They
-- coincide in spelling because they lean on the same fact about uuids, not because they mean the same
-- thing. A nullable column with a partial unique index would have been the alternative and it buys
-- nothing: the upsert needs one arbitrable key, and `on conflict (queue, org_id)` cannot infer a
-- partial index without repeating its predicate at every call site.

-- The claim's candidate CTE reads
-- `where queue = ? and due_at <= now() order by due_at limit 1 for update skip locked`, which is
-- exactly this index and nothing else — leading equality, then the ordering column, so the scan starts
-- at the oldest due tenant and stops at the first row it can lock. The primary key cannot serve it:
-- `org_id` is second there and `due_at` is not in it at all, so every poll would read every signal row
-- of that queue and sort them.
create index idx_queue_signal_due on queue_signal (queue, due_at);

-- BACKFILL: every tenant that already has unfinished work gets a signal, or the deploy that ships the
-- two-step claim silently strands whatever was queued at the moment it rolled. Nothing else would
-- notice — the rows stay PENDING, no error is raised anywhere, and the only symptom is webhooks that
-- never arrive.
--
-- `due_at = now()` rather than the precise next-claimable time, deliberately: a backfill's job is to
-- lose nothing, not to be exact. Every one of these tenants is probed once on the next poll, and the
-- release that follows recomputes the real `due_at` from its own rows. Being early costs one query;
-- being late costs the backlog.
--
-- THE `to_regclass` GUARDS ARE NOT DEFENSIVENESS. The tenant tier's DDL lives in `db/migration/tenant/`
-- and reaches the database through `db/migration/bootstrap/V9999__tenant_pool.sql`, which Flyway runs
-- AFTER every numbered platform migration — so on a fresh database `tenant_pool.webhook_delivery` does
-- not exist yet when this file runs, and a plain reference to it would fail to parse at V56 on every
-- new environment. On an existing database it does exist and the backfill runs. Dynamic SQL is what
-- lets one file be correct in both worlds.
do $backfill$
declare
    platform_scope constant uuid := '00000000-0000-0000-0000-000000000000';
begin
    -- notification_delivery is platform-tier (ADR 0010 §2 row 27) and always present by now.
    insert into queue_signal (queue, org_id, due_at)
    select 'notification', coalesce(d.org_id, platform_scope), now()
      from notification_delivery d
     where d.status in ('PENDING', 'PROCESSING')
     group by coalesce(d.org_id, platform_scope)
    on conflict (queue, org_id) do nothing;

    -- exchange_job is SPLIT; this is its platform copy, which holds exactly the null-org jobs. The
    -- coalesce is not decoration — a row here with an org_id would be the misfiled write ADR 0010 §1
    -- calls the worst failure this design can produce, and giving it a real signal is what lets the
    -- worker drain it instead of leaving it invisible.
    insert into queue_signal (queue, org_id, due_at)
    select 'exchange', coalesce(j.org_id, platform_scope), now()
      from exchange_job j
     where j.status in ('PENDING', 'VALIDATING', 'PROCESSING')
     group by coalesce(j.org_id, platform_scope)
    on conflict (queue, org_id) do nothing;

    if to_regclass('tenant_pool.exchange_job') is not null then
        execute $q$
            insert into platform.queue_signal (queue, org_id, due_at)
            select 'exchange', j.org_id, now()
              from tenant_pool.exchange_job j
             where j.status in ('PENDING', 'VALIDATING', 'PROCESSING')
               and j.org_id is not null
             group by j.org_id
            on conflict (queue, org_id) do nothing
        $q$;
    end if;

    if to_regclass('tenant_pool.webhook_delivery') is not null then
        execute $q$
            insert into platform.queue_signal (queue, org_id, due_at)
            select 'webhook', d.org_id, now()
              from tenant_pool.webhook_delivery d
             where d.status in ('PENDING', 'PROCESSING')
             group by d.org_id
            on conflict (queue, org_id) do nothing
        $q$;
    end if;
end
$backfill$;

-- NOT DONE HERE, AND IT IS A TENANT MIGRATION, NOT AN OMISSION. The two-step claim appends
-- `and org_id = ?` to both arms of `WebhookDeliveryQueue.claim`, and the index those arms walk is
-- V15's `idx_webhook_del_due (next_attempt_at) where status = 'PENDING'` — which orders correctly and
-- then FILTERS on org_id, so a pooled schema holding many tenants' backlogs reads rows it discards.
-- The index that shape wants is `(org_id, next_attempt_at) where status = 'PENDING'` (and its
-- stale-arm twin on `locked_at`), and `webhook_delivery` is tenant-tier: that DDL belongs in
-- `db/migration/tenant/`, under the next free number, not in the platform sequence. Recorded here so
-- the next reader finds the reason rather than re-deriving it from a slow claim. The pool is one
-- schema today and the arms' own LIMITs still bound the read, which is why this is a follow-up and not
-- a blocker.
