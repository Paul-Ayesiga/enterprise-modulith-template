-- Track when a delivery FIRST became throttled by its channel's provider rate limit.
-- throttle-max-age must measure continuous time spent throttled, not age-since-enqueue: an old
-- message from a legitimate burst is not a mis-set-rate victim and must not be dead-lettered for
-- having waited its turn. Cleared whenever the delivery actually gets attempted.

alter table notification_delivery
    add column throttled_since timestamptz;
