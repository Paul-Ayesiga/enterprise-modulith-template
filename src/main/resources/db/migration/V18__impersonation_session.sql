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
-- actor_subject / target_subject are Keycloak subjects (soft refs — app_user lives in this module, but
-- the columns must survive the account being deleted, which is precisely when the trail matters most).
-- org_id is a soft ref to the organization module and is null for an unscoped session.
create table impersonation_session
(
    id             uuid         primary key,
    actor_subject  varchar(64)  not null,       -- the operator: the human answerable for the session
    target_subject varchar(64)  not null,       -- the identity being worn
    org_id         uuid,                        -- tenant the session is scoped to; null when unscoped
    reason         varchar(500) not null,       -- the stated justification; required, >= 8 chars
    mode           varchar(20)  not null,       -- READ_ONLY | WRITE (WRITE needs platform-admin)
    started_at     timestamptz  not null,
    expires_at     timestamptz  not null,       -- server-clamped TTL; expiry is evaluated on read
    ended_at       timestamptz,                 -- null while live; set on end/supersede, never cleared
    ended_by       varchar(64),                 -- who ended it (the actor, or a platform admin)
    version        bigint       not null,
    created_at     timestamptz  not null,
    created_by     varchar(100),
    updated_at     timestamptz,
    updated_by     varchar(100)
);

-- Serves the one-active-session-per-(actor, target) check on every open: partial, because a live
-- session is a vanishing fraction of the rows and the ended ones would be dead weight on the scan.
-- Deliberately an index and not a unique constraint: "active" also depends on expires_at, so a unique
-- index over `ended_at is null` would reject re-opening a session against someone whose previous one
-- merely expired — and it would surface a lost race as a 500 instead of the supersede the service
-- performs.
create index idx_impersonation_active on impersonation_session (actor_subject, target_subject)
    where ended_at is null;

-- The caller's own history, newest first (the keyset cursor sorts on created_at desc, id desc).
create index idx_impersonation_actor on impersonation_session (actor_subject, created_at desc, id desc);
