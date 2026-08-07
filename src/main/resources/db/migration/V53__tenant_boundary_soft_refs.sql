-- ADR 0010 Phase 0: make the tenant boundary REAL, and route nothing.
--
-- ADR 0010 splits the 55 tables into a PLATFORM tier (one copy, global — people, devices, the
-- routing registry, the catalogues) and a TENANT tier (the rows an organization owns and takes with
-- it). Nothing moves in this file. No schema is created, no search_path is set, no DataSource
-- changes. What this file does is remove the five foreign keys that CROSS that line, because every
-- later phase — `alter table … set schema`, `pg_dump -n t_<hex>`, and finally a second database —
-- is impossible while they exist. A foreign key is the one thing in Postgres that cannot span two
-- databases, so cutting them is the step that has to come first and the step that can be taken now,
-- alone, with the suite green and nothing routed.
--
-- WHY THESE FIVE AND NOT THE OTHER TWELVE. AGENTS §1 has always said "no cross-MODULE foreign keys",
-- and all five of these PASS that rule — they never leave their module. `org_subscription.plan_id →
-- plan` is entirely inside subscription/; `user_device_trust.device_id → user_device` entirely
-- inside access/. The module rule was not failing to catch them, it was never looking, because the
-- tenant boundary is a different partition of the same tables. §1 gained a second clause for exactly
-- this, and `TenantSchemaSelfContainmentTest` is what enforces it from here on. The other twelve
-- FKs are intra-module AND intra-tier and travel whole: `role_permission.role_id → org_role` moves
-- with its tenant, `person_contact.person_id → person` stays on the platform.
--
-- FOUR OF THE FIVE ARE PLAIN DROPS. `membership`, `org_role` and `org_group` point at
-- `organization`, and `org_subscription` points at `plan`; none of them cascades and none of them is
-- doing anything the application does not already do for itself. They become soft refs — the same
-- convention this schema already applies 29 times over (`membership.person_id`, every `created_by`).
-- What guarantees the relationship afterwards is stated per statement below, and the short form is:
-- the tenant schema itself. A row in `t_<hex>.membership` is in that org's schema; that IS the
-- assertion the FK used to make, and unlike the FK it survives the org moving to its own database.
--
-- THE FIFTH IS NOT A DROP, IT IS A REPLACEMENT, and it is the reason this file is long.
-- `user_device_trust.device_id → user_device(id) ON DELETE CASCADE` is load-bearing; V51's header
-- says so in its own words. `user_device` is PLATFORM (a device belongs to a human, not to a tenant)
-- and `user_device_trust` is TENANT (a grant BY an organization), so the FK crosses and cannot
-- survive — but the cascade behind it is a security control, and dropping it alone would leave a
-- revoked device trusted forever. §2 replaces it with three mechanisms, none of which is sufficient
-- on its own. Read §2 before touching any of them.
--
-- APPLIED to the 5.6M-row review database (Postgres 18.4) in one transaction, as Flyway runs it.
-- Every EXPLAIN quoted below is (ANALYZE, BUFFERS) against that database, warm, repeated until
-- stable — the method V49/V50/V52 established. Plain `create index`, never `concurrently`: V52's
-- header derives this and AGENTS §4.6 carries it (a `-- flyway:executeInTransaction=false` comment
-- is INERT on flyway-core 12.4.0; only a `.sql.conf` sidecar works, and none of these builds is
-- large enough to want one).

-- ---------------------------------------------------------------------------------------------
-- 1. The four plain cuts. Each says what guarantees the relationship once the constraint is gone.
-- ---------------------------------------------------------------------------------------------

-- membership.org_id, org_role.org_id, org_group.org_id -> organization(id).
--
-- `organization` is the routing registry and ADR 0010 §2.1 deliberately does NOT mirror it into
-- tenant schemas: a per-tenant copy of a routing row can drift from the authority, and an out-of-date
-- copy of "is this org suspended" is a worse failure than no copy at all. So these three cannot be
-- re-pointed at a local table; they become soft refs.
--
-- WHAT GUARANTEES THE RELATIONSHIP NOW. Three things, in the order they act. (1) The schema: after
-- Phase 2 every one of these rows lives in its own org's schema, so "which organization does this
-- membership belong to" is answered by where the row IS, not by a constraint — and that answer keeps
-- working after the org moves to its own database, which is precisely what the FK could not do.
-- (2) The write paths: `OrgProjectionWriter.projectWithOwner` and `MemberService` only ever mint
-- these rows inside a transaction that has already resolved a real organization, so an org_id
-- pointing at nothing is not a state the application can produce. (3) `SoftDeletePurgeJob` — which
-- is where the loss is real and worth naming: the FK's `NO ACTION` used to make purging an
-- `organization` that still had memberships fail loudly. It now succeeds and leaves orphans behind.
-- That is not a regression the purge order can fix (it is cross-tier by construction), and it is the
-- price ADR 0010 §5 accepts; the tenant-schema drop in Phase 5 removes the whole schema at once,
-- which is the real answer.
--
-- Unqualified `drop constraint`, no `if exists` — AGENTS §4.5: this project created these names, so
-- a drifted one must fail the deploy rather than silently leave the constraint in place.
alter table membership
    drop constraint membership_org_id_fkey;
alter table org_role
    drop constraint org_role_org_id_fkey;
alter table org_group
    drop constraint org_group_org_id_fkey;

-- org_subscription.plan_id -> plan(id).
--
-- This one is the clearest statement of why the tier axis is not the module axis: both tables sit in
-- subscription/, both are written by the same service, and the FK is perfectly sound TODAY. It is
-- also impossible tomorrow. `plan` is a platform CATALOGUE — one price list, curated by the platform,
-- snapshot-copied into an extracted deployment and then deliberately allowed to diverge (§6, item 4
-- of the extraction bundle) — while `org_subscription` is the tenant's own row. Cross-SCHEMA the FK
-- would still work; cross-DATABASE (hop 2) it cannot exist at all, and there is no version of "the
-- tenant runs on its own Postgres" in which this constraint survives. Cutting it now, while both
-- tables are still co-located and the suite can prove nothing broke, is the entire point of Phase 0.
--
-- WHAT GUARANTEES IT NOW: `SubscriptionService` resolves the plan through the repository before it
-- writes, so a subscription cannot be created against a plan id that does not exist; and AGENTS §5.2
-- already names the destiny — a `PlanCatalog` port, which is what an extracted deployment will read
-- instead of a joined table. The failure this admits is a plan hard-deleted out from under a live
-- subscription, which `SoftDeletePurgeJob` can now do without complaint. `plan` is not in
-- PURGE_ORDER (it is not soft-deletable) and nothing else hard-deletes it, so the exposure today is
-- a hand-written DELETE — loud enough in an audit, and the port is the real fix.
alter table org_subscription
    drop constraint org_subscription_plan_id_fkey;

-- ---------------------------------------------------------------------------------------------
-- 2. user_device_trust: the cascade that was doing security work, replaced by three mechanisms.
--    FORGET ANY ONE OF THEM AND REVOKED DEVICES STAY TRUSTED.
-- ---------------------------------------------------------------------------------------------

-- WHAT THE CASCADE WAS FOR. V51's header: "a revoked device must leave no live trust grant behind".
-- `org_security_policy.require_trusted_device` is enforced on every request of an org that turns it
-- on, and the enforcement query — `UserDeviceTrustRepository.isTrusted` — joined `user_device` for
-- three things: `person_id`, `fingerprint`, and `deleted_at is null`. That last predicate is the
-- control. Revoking a device is a SOFT delete, so the device row stays; the join is what stopped its
-- surviving grant from continuing to satisfy the policy. The `ON DELETE CASCADE` covered the other
-- half — the nightly hard delete, thirty days later.
--
-- WHAT BREAKS IF YOU ONLY DENORMALIZE. Once the join is gone, nothing in `user_device_trust` can see
-- `deleted_at`, and the query has no way to ask whether the device is still live. Worse than "a
-- revoked device keeps passing": `uq_user_device_person_fingerprint_live` is PARTIAL on
-- `deleted_at is null`, so re-registering a revoked fingerprint mints a BRAND NEW device row with a
-- new id (DeviceService.register cannot see the dead one — @SQLRestriction hides it). A stale grant
-- keyed on (org_id, person_id, fingerprint) would then vouch for a device this organization has
-- never seen, let alone blessed. That is a strictly worse bug than the one V51 fixed.
--
-- SO THE INVARIANT MOVES: the grant row's EXISTENCE must mean the device is live. There is no
-- predicate that can express that any more, so three mechanisms maintain it instead.
--
--   (a) THE BACKFILL BELOW DELETES, IT DOES NOT ONLY COPY. Every grant whose device is already
--       soft-deleted is a row the old join was SUPPRESSING. Copy person_id/fingerprint onto it and
--       you have not migrated it — you have PROMOTED it, turning a correctly-denied grant into a
--       live one at deploy time, silently, for every such row at once. This is the trap in this
--       whole migration and it is three lines below.
--   (b) EVENT-DRIVEN CLEANUP — `access.DeviceRevoked` → `DeviceTrustRevocationListener`, which
--       deletes every org's grant over that device. The fast path, milliseconds after commit, and
--       the one that still works when the grants live in N tenant schemas and a synchronous delete
--       from the platform side cannot reach them.
--   (c) THE RECONCILER — `SoftDeletePurgeJob`'s CASCADES entry for `user_device`, an anti-join that
--       deletes every grant whose device is not live. It catches what (b) cannot: the compliance
--       erasure path soft-deletes `user_device` rows by raw SQL and publishes no event, a permanently
--       failing listener, and any future writer nobody remembers to wire up. ADR 0010 §8 Q2 states
--       the rule it follows — no projection ships without its reconciler.
--
-- The residual window is (b)'s async delivery: a revoked device stays trusted for the milliseconds
-- between commit and listener, and for at most a night on the paths only (c) reaches. That is the
-- honest cost, it is written in ADR 0010 §2.1, and it is bounded — where "a stale grant vouches for
-- a re-registered device forever" would not be.

alter table user_device_trust
    add column person_id   uuid,
    add column fingerprint varchar(100);

-- (a) THE SUPPRESSED GRANTS. Read the paragraph above before deleting this statement because it
-- "removes rows the migration should be copying". These are grants over devices their owner already
-- revoked; `isTrusted`'s `d.deleted_at is null` has been denying them all along, and the whole point
-- of the denormalization is that nothing can deny them afterwards. Deleting them is what makes the
-- new lookup mean the same thing as the old one on the day it ships.
--
-- The FK is still in place at this point, ON PURPOSE and in this order: it is what guarantees every
-- surviving grant has a `user_device` row for the backfill to read, so the UPDATE below cannot leave
-- a NULL behind. Drop it first and a grant orphaned by some earlier accident would survive the
-- delete, fail the backfill silently, and then fail `set not null` — at which point you would be
-- debugging a constraint error instead of reading this comment.
delete
from user_device_trust t
where exists (select 1
              from user_device d
              where d.id = t.device_id
                and d.deleted_at is not null);

update user_device_trust t
set person_id   = d.person_id,
    fingerprint = d.fingerprint
from user_device d
where d.id = t.device_id;

-- NOT NULL, both. A grant with a null person_id or fingerprint could never match the enforcement
-- lookup, so it would fail SAFE — and that is exactly why it must be impossible: a row that silently
-- never matches is a trust grant an admin can see in the UI and the filter will never honour, with
-- no error anywhere. Loud at write time beats invisible at read time.
alter table user_device_trust
    alter column person_id set not null,
    alter column fingerprint set not null;

-- The cut itself. Everything above exists so that this line does not change what the platform
-- enforces.
alter table user_device_trust
    drop constraint user_device_trust_device_id_fkey;

-- The enforcement index: (org_id, person_id, fingerprint) is exactly `isTrusted`'s predicate, in
-- selectivity order, and it makes the check an Index Only Scan — the grant row never has to be
-- visited at all. Measured on 20,000 grants in one organization (a fleet-managed enterprise, the
-- shape that stresses the old plan hardest), warm:
--     before  Nested Loop: uq_user_device_person_fingerprint_live -> user_device_trust_pkey
--                                                    7 buffers, 0.019 ms
--     after   Index Only Scan, this index            4 buffers, 0.016 ms
-- BE HONEST ABOUT THAT NUMBER: it is two index descents becoming one, not a fix for something slow.
-- The reason this index exists is not speed, it is that the check now touches ONE table — so it can
-- be answered inside a tenant schema, by a tenant database, with the platform unreachable. 5224 kB
-- at 50,000 grants.
create index idx_user_device_trust_enforcement on user_device_trust (org_id, person_id, fingerprint);

-- V51's idx_user_device_trust_org STAYS, and it is now a strict column prefix of the index above —
-- which V52 §6(a) settles is not by itself a reason to drop one. It serves the reverse question
-- ("what does this org trust", the admin listing), which scans a whole org's grants rather than
-- probing one; at 16 bytes an entry against 48 it walks a third of the pages, and the gap grows with
-- the size of the org. Nothing here changed that trade-off, so nothing here re-litigates it.

-- ---------------------------------------------------------------------------------------------
-- 3. The org_id-leading indexes ADR 0010 §4.1 found missing. A tenant query filters org_id FIRST —
--    that is true of the promotion copy (`create table as select … where org_id = ?`), of the
--    per-tenant claim Phase 3 builds, and of the nightly assertion §1 describes for catching a
--    misrouted write. Each of the three below turns a full scan into an index probe; the fourth
--    candidate was measured and REJECTED (§4).
-- ---------------------------------------------------------------------------------------------

-- notification_delivery. The worst of the four by a wide margin, and the one ADR 0010 quotes.
-- 300,000 rows / 79 MB, 5,000 distinct orgs, and NOT ONE index mentions org_id — both existing
-- indexes are partial on `status`, so `where org_id = ?` cannot use either:
--     before  Parallel Seq Scan   6611 buffers, 99,980 rows discarded per worker, 10.2 / 11.0 ms
--     after   Index Scan            63 buffers,                                    0.028 ms
-- 105x the buffers.
--
-- PLAIN (org_id), NOT (org_id, created_at desc) — measured both, and the composite is worth nothing
-- here: 64 buffers / 0.023 ms for 12 MB, against 63 buffers / 0.028 ms for 2144 kB. The reason the
-- narrow one is so small is btree deduplication over 5,000 distinct values in 300,000 rows; adding a
-- timestamp defeats the deduplication and sextuples the index to buy noise. If Phase 3's two-step
-- claim later wants an ordering column it should be `next_attempt_at` with the claim's partial
-- predicate, not `created_at` — a different index, decided with that statement in hand.
--
-- NAME THE READER, because V52's rule is that an index's worth is a fact about statements: there is
-- no org-leading reader in the code TODAY. `NotificationDeliveryQueue.claim` is a cluster-wide
-- SKIP LOCKED sweep and the mart reads platform-wide. It ships now anyway, and deliberately: the
-- statement it serves is the extraction drain (§6 item 11) and Phase 3's per-tenant claim, both of
-- which run under a freeze window, and building 2 MB of index over 300k rows during that window is
-- the one cost this file can pay in advance for free. This is the same shape as V52 §3's
-- idx_org_group_role_fk, whose only reader is Postgres's own RI trigger — an index with no `select`
-- behind it is not automatically dead weight, but it does owe the reader this paragraph.
create index idx_notification_delivery_org on notification_delivery (org_id);

-- impersonation_session. 20,000 rows, both existing indexes lead with actor_person_id (the operator),
-- which is the right key for "what has this operator done" and useless for "who reached into this
-- org". 40 rows of 20,000:
--     before  Seq Scan     400 buffers, 19,960 rows discarded, 0.949 ms
--     after   Index Scan     4 buffers,                        0.011 ms
-- 100x, for 160 kB. The reader that makes this more than groundwork is the one ADR 0010 §5 item 5
-- names: `impersonation_session` stays PLATFORM while the tenant's audit rows carry `impersonation_id`,
-- so extracting an org has to pull the sessions naming it or its audit trail loses the operator's
-- stated reason. That extract is `where org_id = ?`.
create index idx_impersonation_session_org on impersonation_session (org_id);

-- signup_request. NOT MEASURED, and stated rather than dressed up: the table is EMPTY in the review
-- database (0 rows), so there is no plan to quote and no ratio to claim. It is here on the shape of
-- the table, not on a number — `org_id` has no index of any kind, and the column's whole purpose is
-- the completion link (`SignupRequest.completed(orgId, …)`, set at completion because the row exists
-- before its tenant does, ADR 0010 §2 row 47). "Which signup produced this organization" is the
-- question a support investigation and the extraction bundle both ask, and today it is a seq scan on
-- a table that grows with every signup attempt forever.
--
-- PLAIN, not partial on `where org_id is not null`, even though most in-flight rows carry a null.
-- V52 §6(b)'s trap is the reason: a partial index is invisible to a query that does not prove its
-- predicate, and the saving here is a few kB on a table with no rows in it. Not worth a proof
-- obligation on every future reader.
create index idx_signup_request_org on signup_request (org_id);

-- ---------------------------------------------------------------------------------------------
-- 4. NOT DONE: feature_flag_org_override. ADR 0010 §4.1 lists it as a fourth missing index; it was
--    measured and it is already covered. Recorded here so the next audit does not re-propose it
--    from the schema — where it looks obviously missing, because the org_id it needs is the SECOND
--    column of somebody else's unique constraint.
-- ---------------------------------------------------------------------------------------------

-- `feature_flag_org_override_flag_key_org_id_key` is UNIQUE (flag_key, org_id) — org_id is not the
-- leading column, which on any Postgres before 18 would have made it useless for `where org_id = ?`.
-- On 18.4 it is not: the planner SKIP SCANS the leading column and answers from that index.
--     existing unique index (skip scan)   17 buffers, 0.072 ms
--     a dedicated (org_id) index           3 buffers, 0.007 ms   (+72 kB)
-- A 5.7x buffer ratio that looks like the other three until you notice the units. The deciding
-- number is the floor: with every index-scan method disabled the whole table is a 21-buffer /
-- 0.052 ms sequential scan, because it is 2,000 rows in 168 kB. There is nothing here for an index
-- to save — the worst plan Postgres can pick is already faster than the best plan on the tables
-- above, and 100 distinct flag_keys is what the skip scan costs, on a catalogue curated by hand.
--
-- WHAT WOULD CHANGE THIS: flag_key cardinality, not row count. The skip scan pays one descent per
-- distinct leading value, so a few thousand flags would make it linear where a dedicated index is
-- constant. Re-measure if the flag catalogue ever grows an order of magnitude; do not re-propose
-- this on the grounds that org_id "has no index", because it does — it is just not first.
