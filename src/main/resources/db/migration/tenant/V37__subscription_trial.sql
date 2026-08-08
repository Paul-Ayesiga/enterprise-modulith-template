-- Trial mode for paid plans. A PRO/ENTERPRISE subscription may run as a TRIALING trial with an end
-- date and full access; when the trial lapses unpaid the TrialExpiryJob flips it to PAUSED (a new
-- status) and the org goes READ-ONLY (org-scoped writes answer 402) until a plan is assigned or a
-- payment lands. FREE plans have nothing to trial. No new table — this is the org_subscription row
-- gaining a trial deadline; PAUSED joins ACTIVE | TRIALING | PAST_DUE | CANCELLED on the status.

alter table org_subscription
    add column trial_ends_at timestamptz;   -- set while TRIALING; the instant the trial lapses

-- The expiry scan reads only live TRIALING rows by their deadline — keep it index-only.
create index idx_org_subscription_trial_expiry
    on org_subscription (trial_ends_at)
    where status = 'TRIALING' and deleted_at is null;
