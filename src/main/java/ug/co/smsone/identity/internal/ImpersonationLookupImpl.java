package ug.co.smsone.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.shared.security.ImpersonatedPrincipal;
import ug.co.smsone.shared.security.ImpersonationLookup;

/**
 * Implements the shared {@link ImpersonationLookup} port — makes {@code ImpersonationFilter} live.
 *
 * <p>Reads the row on every impersonated request, uncached on purpose: "ending a session denies the
 * very next request" is the guarantee the feature is sold on, and a cached session would keep the
 * reach open for the length of the TTL after an operator, or the admin cutting them off, ended it.
 */
@Component
class ImpersonationLookupImpl implements ImpersonationLookup {

    private final ImpersonationSessionRepository sessions;
    private final UserRepository users;
    private final Clock clock;

    ImpersonationLookupImpl(ImpersonationSessionRepository sessions, UserRepository users, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ImpersonatedPrincipal> activeSession(String sessionId, String actorSubject) {
        if (sessionId == null || actorSubject == null) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(sessionId.trim());
        } catch (IllegalArgumentException ex) {
            // A malformed id is an unknown session, not an error: the caller controls this header, and
            // letting a typo become a 500 would make the shape of the id guessable from the status code.
            return Optional.empty();
        }
        Instant now = clock.instant();
        return sessions.findByIdAndActorSubject(id, actorSubject)
                .filter(session -> session.isActive(now))
                .filter(session -> stillHasAccess(session.getActorSubject()))
                .flatMap(this::principalIfTargetStillHasAccess);
    }

    /**
     * The target's access check and its display e-mail come from ONE read — the previous shape read
     * the same row twice per impersonated request. The rules are {@link #stillHasAccess}'s, verbatim:
     * DISABLED and soft-deleted deny; absent-and-never-provisioned passes (with no e-mail to show).
     */
    private Optional<ImpersonatedPrincipal> principalIfTargetStillHasAccess(ImpersonationSession session) {
        User target = users.findBySubject(session.getTargetSubject()).orElse(null);
        if (target == null) {
            return users.existsDeletedBySubject(session.getTargetSubject())
                    ? Optional.empty()
                    : Optional.of(principal(session, null));
        }
        if (target.getStatus() == ProvisioningStatus.DISABLED) {
            return Optional.empty();
        }
        return Optional.of(principal(session, target.getEmail()));
    }

    /**
     * Both accounts are re-read on every request, not just at open time, because a session outlives the
     * decision that authorized it by up to its whole TTL.
     *
     * <p>For the TARGET this deliberately duplicates what {@code ProvisioningGateFilter} would conclude
     * about the same row. Delegating to it was the earlier design and it is not enough: the gate is a
     * documented kill switch ({@code app.provisioning.gate-enabled}), so a deployment that turns it off
     * would leave a disabled or erased account reachable through a live session for the rest of its
     * lifetime. A guarantee this feature is sold on cannot be borrowed from a switch someone else owns.
     *
     * <p>For the ACTOR nothing else checks at all: the swap happens at {@code @Order(-2)}, so the gate
     * downstream sees the target, and offboarding an operator — DISABLED, or soft-deleted — would
     * otherwise stop their ordinary requests while leaving their impersonated ones working. The reach
     * has to die with the operator's own account, not with the TTL.
     *
     * <p>An absent-and-not-deleted row passes: it means "never provisioned", which the gate refuses on
     * the operator's own requests anyway, so no such actor could have opened this session in the first
     * place — and refusing it here would instead break every deployment running with the gate off.
     */
    private boolean stillHasAccess(String subject) {
        User user = users.findBySubject(subject).orElse(null);
        if (user == null) {
            return !users.existsDeletedBySubject(subject);
        }
        return user.getStatus() != ProvisioningStatus.DISABLED;
    }

    /** The e-mail is best-effort display data; the access decision was made by the caller. */
    private ImpersonatedPrincipal principal(ImpersonationSession session, String email) {
        return new ImpersonatedPrincipal(session.getId(), session.getActorSubject(), session.getTargetSubject(),
                email, session.getOrgId(), session.getMode().writeCapable());
    }
}
