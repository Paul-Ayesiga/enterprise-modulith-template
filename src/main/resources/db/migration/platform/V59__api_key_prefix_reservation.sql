-- ADR 0010 Phase 6, extraction bundle item 8: "RE-MINTED api_key rows; the platform revokes the old
-- ones and PERMANENTLY RESERVES their prefixes."
--
-- WHY A SECOND TABLE AND NOT THE INDEX WE ALREADY HAVE. V29 built
--     create unique index uq_api_key_prefix_live on api_key (prefix) where deleted_at is null
-- and that predicate is the whole problem for this requirement. Revoking a key is a SOFT delete, so a
-- revoked key's row leaves the index and its prefix becomes mintable again. That is correct for the
-- question the index answers ("which LIVE key is this prefix") and wrong for the question extraction
-- asks ("may this prefix ever mean anything again"). A reservation stored in `api_key` would therefore
-- have to be a live row — an undeletable phantom key that `findByPrefix` resolves and the authenticator
-- then fails to match — which is a worse answer than a table whose only job is to say "taken, forever".
--
-- WHAT GOES WRONG WITHOUT IT. An extracted tenant's clients keep presenting the credentials they had.
-- Those keys are revoked on the platform, so today they get 401 — correct. But the prefix is only 48
-- bits (`sk_` + 12 hex, ApiKeyHashing.mint), and the moment the platform re-mints that prefix for a
-- DIFFERENT organization, a stale credential from the departed tenant starts resolving to another
-- tenant's key row. The secret hash still has to match, so it is not an authentication bypass — it is
-- a cross-tenant lookup collision on the one code path (`ApiKeyAuthenticatorImpl`) that runs BEFORE any
-- tenant is known, and ADR 0010 §2.1 keeps `api_key` platform-tier precisely because that lookup
-- cannot be scoped. 48 bits is small enough that "it will never collide" is a probability argument, and
-- this table replaces it with a constraint.
--
-- THE RESERVATION IS ONLY REAL BECAUSE THE MINT PATH READS IT. A table nothing consults is a comment
-- with a primary key: `ApiKeyPrefixReservations.mintUnreservedPrefix()` is the single mint used by
-- createOrgKey, createPlatformKey and rotateOrgKey, and it re-mints until the candidate is free.
--
-- PLATFORM TIER, always, for the same reason `api_key` is: it is read before any tenant axis exists.
-- It is deliberately NOT part of an extraction bundle — a lifted deployment mints its own keys into its
-- own prefix space, and inheriting the platform's reservations would only forbid prefixes that mean
-- nothing there.

create table api_key_prefix_reservation
(
    prefix      varchar(20)  not null primary key,   -- the public half that may never be minted again
    org_id      uuid,                                -- organization.id the key belonged to; null = a
                                                     -- platform key. Soft ref, no FK: apikeys and
                                                     -- organization are different modules (AGENTS §1),
                                                     -- and after an extraction the org row itself may
                                                     -- be gone while the reservation must not be.
    reserved_at timestamptz  not null,
    reason      varchar(200) not null                -- why it was burned, for the operator who finds a
                                                     -- prefix refused years later
);

-- Serves the operator question "what did this organization's extraction burn", which is the only
-- non-primary-key access this table has. Not partial: a reservation is never released, so there is no
-- live/dead half to exclude.
create index idx_api_key_prefix_reservation_org on api_key_prefix_reservation (org_id, reserved_at desc);
