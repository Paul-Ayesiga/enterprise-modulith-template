-- The recipient's organization context, carried from dispatch to the channel sender so per-org
-- integration choices (which SMS provider serves THIS org) resolve at send time. Nullable: platform
-- notifications (admin alerts) have no org and fall through to the platform default / env config.
alter table notification_delivery add column org_id uuid;
