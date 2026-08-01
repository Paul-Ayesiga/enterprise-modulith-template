-- Impersonation gives a request two identities, so the trail needs two columns instead of one.
--
-- `actor` keeps its name and gains precision: it is the ACCOUNTABLE HUMAN. Outside a session that is
-- simply the caller. Inside one it is the operator who opened the session — never the identity they
-- are wearing, even though every other durable column written by that request (created_by, updated_by,
-- membership rows) records the target, because those describe what the account now looks like and this
-- describes who made it look that way.
--
-- `on_behalf_of` holds the worn identity, and `impersonation_id` correlates the row back to the
-- session row, which is where the stated reason, the tier that authorized it and the expiry live. Both
-- are null for every ordinary request, and that is the useful part: a non-null `on_behalf_of` is the
-- single predicate for "an operator did this inside someone else's account".
--
-- impersonation_id is a SOFT reference. impersonation_session belongs to the identity module and
-- audit_log to the audit module; this project puts no foreign keys across a module boundary.
alter table audit_log add column on_behalf_of     varchar(64);
alter table audit_log add column impersonation_id uuid;

-- Serves the review query "everything done while this user was being impersonated". Partial because
-- the column is null on all but a vanishing fraction of rows, and a total index would be dead weight
-- on every audited write forever.
create index idx_audit_on_behalf_of on audit_log (on_behalf_of) where on_behalf_of is not null;
