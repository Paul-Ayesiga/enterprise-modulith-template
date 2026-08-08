-- Impersonation sessions: the standing record of an operator acting inside someone else's account.
--
-- NOT SOFT-DELETABLE, and that is the whole design decision rather than an oversight. Every other
-- aggregate in this schema records its deletion (V17); this one must not offer deletion at all,
-- because the operator who opened a session is exactly the person a delete would serve. A soft-delete
-- flag here would let an oversight tool erase its own oversight, which inverts the point of the
-- feature. Sessions END — `ended_at` is set and the row stays forever — for the same reason audit_log
-- is append-only. The entity therefore extends BaseEntity, not SoftDeletableEntity, and this table has
-- no `deleted_at` column and no entry in SoftDeletePurgeJob.PURGE_ORDER.
--
-- Nor is it an AggregateRoot: nothing outside the identity module reacts to a session opening, and the
-- durable trail is the audit_log row written next to it (platform.impersonation_started / _ended /
-- _superseded), not a domain event.
--
-- actor_person_id / target_person_id / ended_by_person_id are person.id, and they are SOFT refs with
-- NO foreign key even though person (V10) lives in this same module and the FK would therefore be
-- legal. That is the exception being made deliberately: these columns must survive the account being
-- deleted, which is precisely when the trail matters most, and an FK would hand the deletion path a
-- vote on whether the oversight record may continue to exist.
-- org_id is organization.id, a soft ref to the organization module, null for an unscoped session.

create table impersonation_session
(
    id                  uuid         primary key,
    actor_person_id     uuid         not null,       -- the operator: the human answerable for the session
    target_person_id    uuid         not null,       -- the identity being worn
    target_display      varchar(320),                -- frozen label for the target; see below
    org_id              uuid,                        -- tenant the session is scoped to; null when unscoped
    reason              varchar(500) not null,       -- the stated justification; required, >= 8 chars
    mode                varchar(20)  not null,       -- READ_ONLY | WRITE (WRITE needs platform-admin)
    started_at          timestamptz  not null,
    expires_at          timestamptz  not null,       -- server-clamped TTL; expiry is evaluated on read
    ended_at            timestamptz,                 -- null while live; set on end/supersede, never cleared
    ended_by_person_id  uuid,                        -- who ended it (the actor, or a platform admin)
    version             bigint       not null,
    created_at          timestamptz  not null,
    created_by          uuid        ,
    updated_at          timestamptz,
    updated_by          uuid        
);

-- WHY target_display EXISTS AT ALL, being the only denormalised copy in this schema: the value of
-- this trail is realised AFTER the account is gone, and a person id that resolves to nothing reads
-- worse than the string it replaced — "who was 6f3a…?" is not a question an auditor should have to
-- answer by excavation. The target's email or display name is therefore COPIED here when the session
-- opens and never refreshed; it is a label, not a lookup, and the ids above remain the truth.
-- Nullable on purpose: a person with no address and no display name must still be supportable, and
-- refusing to open an oversight session over a missing label would be the wrong failure.

-- Serves the one-active-session-per-(actor, target) check on every open: partial, because a live
-- session is a vanishing fraction of the rows and the ended ones would be dead weight on the scan.
-- Deliberately an index and not a unique constraint: "active" also depends on expires_at, so a unique
-- index over `ended_at is null` would reject re-opening a session against someone whose previous one
-- merely expired — and it would surface a lost race as a 500 instead of the supersede the service
-- performs.
create index idx_impersonation_active on impersonation_session (actor_person_id, target_person_id)
    where ended_at is null;

-- The caller's own history, newest first (the keyset cursor sorts on created_at desc, id desc).
create index idx_impersonation_actor on impersonation_session (actor_person_id, created_at desc, id desc);
