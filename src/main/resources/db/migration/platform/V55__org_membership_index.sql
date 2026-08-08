-- ADR 0010 §2.1: the platform-side answer to "which organizations does this person belong to?".
--
-- WHY THIS TABLE EXISTS AT ALL. `membership` is tenant-tier — a tenant that cannot answer "who is in
-- this org, with what role" out of its own schema is not extractable, which is the whole point of the
-- split. Every read of it is single-tenant, with exactly ONE exception: the person-first read.
-- `GET /api/v1/me/organizations` asks a question no tenant schema can answer, because the answer spans
-- tenants and the caller has not chosen one yet. Fanned out, that is a query per tenant schema on a
-- route a multi-org human hits at every sign-in; through this table it is one indexed lookup on a
-- connection that is already on the platform axis.
--
-- THE INVARIANT: the index ROUTES, the tenant schema AUTHORIZES. The row is three routing keys and
-- nothing else — no role_id, no version, no created_by — so there is nothing here to authorize WITH.
-- That is what makes the harmless direction of drift (an index row with no membership) harmless: the
-- switcher offers an org that then denies everything, because the authoritative check runs on arrival
-- and reads the tenant.
--
-- Runs in the `platform` schema like every other platform-tier table (this directory's default), and
-- is created UNQUALIFIED for that reason — the same form every other file here uses.
create table org_membership_index
(
    person_id uuid        not null,
    org_id    uuid        not null,
    status    varchar(20) not null,
    primary key (person_id, org_id)
);

-- NO FOREIGN KEY TO `membership`, AND THERE CANNOT BE ONE. The two tables are in different tiers, and
-- AGENTS §1's second FK clause forbids a constraint across that line — a cross-tier FK is precisely
-- what stops `pg_dump -n <schema>` producing a restorable tenant, and after Phase 7 the two are not
-- even in the same database. `person_id` and `org_id` are soft refs for the same reason `membership`'s
-- are. OrgMembershipIndexReconciler is what replaces the missing constraint, which is the same trade
-- V53 made for `user_device_trust`.
--
-- No `version`, `created_at` or `deleted_at` either: this carries no history, it is written inside the
-- same transaction as the `membership` row it mirrors, and it is DELETED rather than tombstoned when
-- the seat ends. A tombstone would be a second thing to remember to clear.

-- The org-first sweep. The primary key already serves the person-first read (`where person_id = ?`),
-- which is the hot one; this covers the reconciler's per-org DELETE and the orphan sweep's anti-join,
-- both of which key on org_id alone and would otherwise sequential-scan the whole platform's routing
-- rows once a night.
create index idx_org_membership_index_org on org_membership_index (org_id);
