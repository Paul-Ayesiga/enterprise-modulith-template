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
 *
 * <p>No translation happens here any more, in either direction. The edge holds person ids and the
 * session row holds person ids, so this class reads a row and checks two accounts — the two subject
 * round-trips it used to perform (actor in, target out) existed only to feed a principal that spoke
 * Keycloak's vocabulary, and both are gone with it.
 */
@Component
class ImpersonationLookupImpl implements ImpersonationLookup {

    private final ImpersonationSessionRepository sessions;
    private final PersonRepository persons;
    private final Clock clock;

    ImpersonationLookupImpl(ImpersonationSessionRepository sessions, PersonRepository persons, Clock clock) {
        this.sessions = sessions;
        this.persons = persons;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ImpersonatedPrincipal> activeSession(String sessionId, UUID actorPersonId) {
        if (sessionId == null || actorPersonId == null) {
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
        return sessions.findByIdAndActorPersonId(id, actorPersonId)
                .filter(session -> session.isActive(now))
                .filter(session -> stillHasAccess(session.getActorPersonId()))
                .flatMap(this::principalIfTargetStillHasAccess);
    }

    /**
     * The target's access check happens on the person row, and nothing else is read.
     *
     * <p>A target with NO Keycloak link can now be worn. That used to be refused because the swapped
     * principal carried a subject and there would have been none; it carries a person id now, which is
     * what every downstream component keys on, so the guard was protecting a field that no longer
     * exists. No label travels either — the session's frozen {@code target_display} is the impersonation
     * API's business, and a decision that keyed on a name instead of an id is a bug waiting for two
     * people to share one.
     *
     * <p>The access rules are unchanged: DISABLED and soft-deleted deny; absent-and-never-provisioned
     * passes.
     */
    private Optional<ImpersonatedPrincipal> principalIfTargetStillHasAccess(ImpersonationSession session) {
        UUID targetPersonId = session.getTargetPersonId();
        Person target = persons.findById(targetPersonId).orElse(null);
        if (target == null && persons.existsDeletedById(targetPersonId)) {
            return Optional.empty();
        }
        if (target != null && target.getStatus() == ProvisioningStatus.DISABLED) {
            return Optional.empty();
        }
        return Optional.of(new ImpersonatedPrincipal(session.getId(), session.getActorPersonId(),
                targetPersonId, session.getOrgId(), session.getMode().writeCapable()));
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
     * <p>An absent-and-not-deleted person passes: it means "never provisioned", which the gate refuses on
     * the operator's own requests anyway, so no such actor could have opened this session in the first
     * place — and refusing it here would instead break every deployment running with the gate off.
     */
    private boolean stillHasAccess(UUID personId) {
        Person person = persons.findById(personId).orElse(null);
        if (person == null) {
            return !persons.existsDeletedById(personId);
        }
        return person.getStatus() != ProvisioningStatus.DISABLED;
    }
}
