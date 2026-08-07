package ug.co.smsone.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for resolving an impersonation session, implemented by the module that owns the session table.
 * The enforcing filter lives in {@code shared}, so the interface does too — the same seam as
 * {@link OrgAuthorization}, and the reason {@code shared} never compile-depends on {@code identity}.
 * With no implementation present the feature is simply absent; nothing default-grants.
 */
public interface ImpersonationLookup {

    /**
     * The live session with this id that was opened <em>by this person</em> — empty when the id is
     * unknown or malformed, when the session has been ended, or when it has expired.
     *
     * <p>Both arguments must match. Resolving by session id alone would make a leaked id — copied from
     * a header, read off a log line — usable by whoever holds it; pinning it to the actor's own person
     * id means a stolen id grants its thief nothing.
     *
     * <p>The actor arrives as a {@code person.id} because the edge no longer holds anything else. That
     * also makes one rule structural rather than remembered: only a person can hold a session, so a
     * machine key — which has no person by construction — can never present one.
     *
     * <p>Expiry is decided here, on read, which is why expiry needs no sweep job: a session past its
     * deadline is already indistinguishable from one that was ended.
     */
    Optional<ImpersonatedPrincipal> activeSession(String sessionId, UUID actorPersonId);
}
