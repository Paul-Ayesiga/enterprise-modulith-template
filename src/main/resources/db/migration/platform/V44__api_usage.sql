-- The platform-side usage ledger: per-org, per-day request counts flushed from the edge (the same
-- consumer identity quotas meter). `exported` marks days already pushed to Kill Bill as usage
-- records; KB additionally dedups by trackingId, so a re-run can never double-bill.
--
-- org_id is organization.id (V11) and it is half the primary key. The thing worth writing down is
-- that this is the only tenant column in the schema whose WRITER LIVES IN ANOTHER DEPLOYABLE: the
-- gateway posts a consumer -> count map, and the report endpoint parses each consumer id as a UUID,
-- skips the ones that are not one (only org-attributable usage can ever bill), and upserts.
--
-- The consumer id IS an organization.id. The gateway's only source of one is API-key introspection,
-- which answers api_key.org_id — our own tenant key. That is what the identity decoupling bought:
-- before it, the tenant key was Keycloak's org id and this seam had to translate.
--
-- This comment used to say the endpoint resolves the consumer through external_organization first.
-- It briefly did, and that was a silent total billing outage: external_organization is keyed by
-- PROVIDER-MINTED ids, an organization.id is never one, so every consumer resolved to nothing and
-- this table took no rows at all while Kill Bill billed zero. Recorded rather than deleted because
-- the mistake is re-reachable: translating here LOOKS right, since it is the same lookup the edge
-- does on a token claim. The sibling quota seam never translated — one string cannot live in two key
-- spaces, and the asymmetry between the two seams is the tell.
--
-- What that translation was reaching for is real and is kept another way: org_id is a soft ref with
-- no cross-module FK (deliberately — this modulith is destined to split), so a well-formed UUID
-- naming no organization inserts perfectly happily and no later query ever finds it, with nothing
-- anywhere failing to notice. The endpoint therefore checks the ids against the organization
-- directory in one batched call before any upsert.

create table api_usage_daily (
    org_id   uuid   not null,           -- organization.id; soft ref, no cross-module FK
    day      date   not null,
    requests bigint not null default 0,
    exported boolean not null default false,
    primary key (org_id, day)
);
