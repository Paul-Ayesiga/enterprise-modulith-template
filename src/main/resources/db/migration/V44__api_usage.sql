-- The platform-side usage ledger: per-org, per-day request counts flushed from the edge (the same
-- consumer identity quotas meter). `exported` marks days already pushed to Kill Bill as usage
-- records; KB additionally dedups by trackingId, so a re-run can never double-bill.
--
-- org_id is organization.id (V11) and it is half the primary key. The thing worth writing down is
-- that this is the only tenant column in the schema whose WRITER LIVES IN ANOTHER DEPLOYABLE: the
-- gateway posts a consumer -> count map, and the report endpoint parses each consumer id as a UUID,
-- skips the ones that are not one (only org-attributable usage can ever bill), and upserts. The
-- gateway has no name for a tenant except the one its own token carries — the KEYCLOAK organization
-- id — so the report endpoint resolves it through external_organization (V11) BEFORE the upsert.
--
-- Resolving on THIS side rather than flipping the gateway in lockstep is deliberate. A lockstep
-- deploy has a window in which the two deployables disagree about what a tenant is called, and the
-- disagreement is silent on both sides: a well-formed UUID that matches no organization row inserts
-- perfectly happily here, because org_id is a soft ref with no cross-module FK and this table has no
-- reason to hold one. Usage then splits across two key spaces and Kill Bill under-bills, with nothing
-- anywhere failing to notice it by. Resolving at the seam also covers the reports already in flight
-- across the cutover, which a lockstep deploy cannot.
create table api_usage_daily (
    org_id   uuid   not null,           -- organization.id; soft ref, no cross-module FK
    day      date   not null,
    requests bigint not null default 0,
    exported boolean not null default false,
    primary key (org_id, day)
);
