-- Impersonation gives a request two identities, so the trail needs two columns instead of one.
--
-- `actor_person_id` keeps its meaning and gains precision: it is the ACCOUNTABLE HUMAN. Outside a
-- session that is simply the caller. Inside one it is the operator who opened the session — never the
-- identity they are wearing, even though every other durable column written by that request
-- (created_by, updated_by, membership rows) records the target, because those describe what the
-- account now looks like and this describes who made it look that way.
--
-- `on_behalf_of_person_id` holds the worn identity, and `impersonation_id` correlates the row back to
-- the session row, which is where the stated reason, the tier that authorized it and the expiry live.
-- Both are null for every ordinary request, and that is the useful part: a non-null on_behalf_of is
-- the single predicate for "an operator did this inside someone else's account".
--
-- Both hold person.id and neither is a foreign key. impersonation_id is a soft ref for the module
-- reason — impersonation_session belongs to identity, audit_log to audit — and the two person ids are
-- soft refs for the retention reason V18 gives: this row outlives the accounts it names, on purpose.
--
-- SPLIT (ADR 0010 §4.1): this is the tenant half of V19. Its sibling is db/migration/platform/V19__audit_log_impersonation.sql.

alter table audit_log add column on_behalf_of_person_id uuid;

alter table audit_log add column impersonation_id       uuid;

-- Serves the review query "everything done while this person was being impersonated". Partial because
-- the column is null on all but a vanishing fraction of rows, and a total index would be dead weight
-- on every audited write forever.
create index idx_audit_on_behalf_of on audit_log (on_behalf_of_person_id)
    where on_behalf_of_person_id is not null;
