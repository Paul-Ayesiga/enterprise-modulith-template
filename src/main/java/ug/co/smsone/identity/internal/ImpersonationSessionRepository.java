package ug.co.smsone.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ImpersonationSessionRepository
        extends JpaRepository<ImpersonationSession, UUID>, JpaSpecificationExecutor<ImpersonationSession> {

    /**
     * The per-request resolution, keyed on BOTH the id and the actor: a session id that leaks — copied
     * from a header, read off a log line — must be worthless to anyone but the operator it was issued
     * to. Whether the session is still live is decided by the caller from {@code endedAt}/{@code
     * expiresAt}, not by this query, so an expired hit is distinguishable from a wrong actor.
     */
    Optional<ImpersonationSession> findByIdAndActorPersonId(UUID id, UUID actorPersonId);

    /**
     * The genuinely LIVE sessions this actor holds against this target — un-ended <em>and</em> un-expired.
     * Expiry belongs in the predicate: a lapsed session's reach stopped at {@code expires_at}, so
     * superseding it would stamp {@code ended_at} at a moment strictly after it, name an {@code
     * ended_by_person_id} who did not end it, and write a {@code _superseded} row claiming a re-issue cut
     * short something that had already stopped. Lapsed rows are left exactly as they are — expiry is a
     * read-time decision here, and nothing sweeps.
     *
     * <p>At most one row survives {@link #lockPair}, which serialises the read-then-insert this feeds.
     * The list return is what makes the method total anyway: rows that predate the lock, or a hand-fixed
     * row, must be superseded rather than blow up on an unexpected second result.
     */
    List<ImpersonationSession> findByActorPersonIdAndTargetPersonIdAndEndedAtIsNullAndExpiresAtAfter(
            UUID actorPersonId, UUID targetPersonId, Instant now);

    /**
     * Serialises opens for one (actor, target) pair for the rest of the caller's transaction, so the
     * one-live-session invariant survives two requests in flight.
     *
     * <p>A lock rather than the constraint §7 normally prefers, because the invariant is not expressible
     * as one: "live" depends on {@code expires_at}, so a unique index over {@code ended_at is null} would
     * reject re-opening against someone whose previous session merely lapsed — see
     * {@code V18__impersonation_session.sql}. Without this, two concurrent opens both read an empty
     * {@code previous} under READ COMMITTED, both insert, and the pair ends up with two live sessions
     * under two different stated reasons, which is precisely the state a review cannot untangle.
     *
     * <p>Advisory rather than row-level for the same reason a {@code select … for update} does not work
     * here: the contended case is the one where the pair has <em>no</em> rows yet, so there is nothing to
     * lock. Hash collisions between unrelated pairs only over-serialise, which is harmless at this call
     * rate. Released with the transaction, so no path can leak it.
     */
    @Query(value = "select count(*) from (select pg_advisory_xact_lock(cast(hashtext(:pair) as bigint))) taken",
            nativeQuery = true)
    long lockPair(@Param("pair") String pair);
}
