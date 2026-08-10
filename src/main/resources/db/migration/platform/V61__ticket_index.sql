-- ADR 0010 §5.1 / §8 Q2: the cross-tenant OPERATOR ticket queue, as one platform-side table.
--
-- WHY IT SHIPS NOW RATHER THAN "AT 50 SILOS". §8 Q2 deferred this projection on a trigger and §5.1 put
-- the trigger at 50 silos. Two things moved the trigger under our feet. Commit 0822943 made
-- `silo-per-org` the DEFAULT placement, so the silo count is now the ORGANIZATION count rather than a
-- hand-promoted exception; and §8 Q1's re-measurement found that the shipped per-home merge costs
-- 1.29-1.39 ms PER HOME, flat from 100 homes upward — 279 ms for one operator queue page at 200 homes,
-- growing linearly, on an interactive surface. The fan-out's cost is O(homes) and this table's is
-- O(page), which is the whole of the change: `TicketFanOut.queue` reads ONE keyset statement here
-- instead of one per home, and stops caring how many tenants exist.
--
-- WHAT IS IN IT, AND WHY NOTHING ELSE IS. Every column here is duplicated data with its own drift, so
-- the set is closed by one question: what does `GET /api/v1/admin/tickets` need in order to (a) order
-- and filter the queue, (b) render the row it returns, and (c) key back into the tenant's own schema
-- for the detail read? (a) is `created_at`/`ticket_id` (the keyset) and `status` (the only filter the
-- route has). (b) is what `TicketResources.TicketAttributes` renders — which is why `opener_person_id`,
-- `category` and `first_response_at` are here and are NOT in §5.1's original nine-column sketch: that
-- sketch predates the wire contract it would have to serve, and a queue that silently rendered three
-- attributes as null would be a change no caller could see coming. (c) is `org_id`.
--
-- Deliberately absent: `version` and `created_by`/`updated_by`/`updated_at` (nobody renders them, and a
-- version here would invite someone to lock against a projection); `first_response_due_at` (the queue
-- shows the RESOLUTION clock); `deleted_at` (below); the ticket's messages (the detail read's job).
--
-- NO `deleted_at`, DELIBERATELY: the row's EXISTENCE is the liveness statement, the same shape
-- `org_membership_index` chose (V55) and for the same reason — a tombstone is a second thing to
-- remember to clear, and this projection already needs a reconciler for the paths that clear nothing.
--
-- THE INVARIANT, and it is `org_membership_index`'s one word for word: THE INDEX RENDERS, THE TENANT
-- SCHEMA ANSWERS. A tenant reading its own tickets (`/api/v1/orgs/{orgId}/tickets`) never touches this
-- table; the operator's single-ticket routes read the tenant's own row; nothing authorizes off it and
-- nothing writes a ticket from it. It is an operator convenience, and the only fact it is allowed to be
-- authoritative about is "which home to look in first" — a hint that falls back to a probe when it is
-- wrong (`TicketFanOut.onTicketsHome`).
--
-- NO FOREIGN KEY TO `ticket`, AND THERE CANNOT BE ONE. `ticket` is TENANT-tier and this is
-- PLATFORM-tier, and AGENTS §1's second FK clause forbids a constraint across that line: it is exactly
-- what stops `pg_dump -n <schema>` producing a restorable tenant, and since ADR 0011 the two are not
-- necessarily even in the same DATABASE. `ticket_id`, `org_id`, `opener_person_id` and
-- `assignee_person_id` are soft refs on the same terms every other cut ref is.
--
-- WHICH MEANS THE WRITE IS NOT ALWAYS ATOMIC, AND THE RECONCILER IS THE REPAIR. For a tenant co-located
-- with primary, `TicketIndex` writes this row on the caller's own connection inside the ticket's own
-- transaction — they commit or roll back together, exactly as today. For a tenant on ANOTHER database
-- (ADR 0011) there is no cross-database transaction and this platform refuses XA, so the pair is
-- eventually consistent and the ordering is chosen so that only the BENIGN direction is left open: an
-- index row may outlive its ticket (a ghost in the operator's queue that 404s on click-through), never
-- the reverse (a customer's ticket the support desk cannot see, which is the Phase 5 failure this
-- module already paid for once). `TicketIndexReconciler` closes both directions nightly, per ADR 0010
-- §8 Q2's rule: no projection ships without its reconciler.
--
-- Runs in the `platform` schema like every other platform-tier table (this directory's default), and is
-- created UNQUALIFIED for that reason — the same form every other file here uses.
create table ticket_index
(
    ticket_id          uuid         not null primary key, -- ticket.id; soft ref, no FK (cross-tier)
    org_id             uuid         not null,             -- organization.id; the home to key back into
    opener_person_id   uuid         not null,             -- person.id; rendered by the queue
    subject            varchar(200) not null,             -- the ticket's TITLE, not a principal
    category           varchar(40),
    priority           varchar(2)   not null,             -- P1..P4
    status             varchar(25)  not null,             -- the queue's only filter
    assignee_person_id uuid,                              -- person.id; a platform operator
    escalated          boolean      not null,
    first_response_at  timestamptz,
    resolution_due_at  timestamptz  not null,
    created_at         timestamptz  not null              -- ticket.created_at; half the keyset
);

-- `ticket_id` alone is the primary key, and that is a claim about the fleet rather than a convenience:
-- ticket ids are v4 UUIDs minted by Hibernate, so they are unique across every tenant schema and every
-- tenant DATABASE. That is the same uniqueness `TicketFanOut`'s merge already leaned on to make
-- (created_at, id) a TOTAL order across homes, and it is what lets the operator's single-ticket routes
-- probe this table by id with no org in the URL.

-- The unfiltered operator page: `order by created_at desc, ticket_id desc limit ?` with the keyset
-- predicate. Column order matches the sort exactly so the scan is a backwards-free index read.
create index idx_ticket_index_queue on ticket_index (created_at desc, ticket_id desc);

-- The same page with `?status=OPEN`, which is how the desk is actually worked. Without the status
-- column leading, a queue narrowed to one status would scan the whole index newest-first looking for
-- matches — cheap while OPEN is common and pathological for a rare status such as CLOSED.
create index idx_ticket_index_status on ticket_index (status, created_at desc, ticket_id desc);

-- The reconciler's per-organization arms: the residue page (`where org_id = ? and ticket_id > ?`) and
-- the pooled-org enumeration. Both key on org_id alone and would otherwise scan the whole platform's
-- queue rows once a night, per home. Same index and the same reason as `idx_org_membership_index_org`.
create index idx_ticket_index_org on ticket_index (org_id, ticket_id);
