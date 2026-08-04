-- The platform-side usage ledger: per-org, per-day request counts flushed from the edge (the same
-- consumer identity quotas meter). `exported` marks days already pushed to Kill Bill as usage
-- records; KB additionally dedups by trackingId, so a re-run can never double-bill.
create table api_usage_daily (
    org_id   uuid   not null,
    day      date   not null,
    requests bigint not null default 0,
    exported boolean not null default false,
    primary key (org_id, day)
);
