package ug.co.smsone.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.security.PlatformRole;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Issues, lists and ends impersonation sessions. <b>Every guardrail lives here</b>, not in the filter
 * that enforces them: the filter's job is to apply a session that already exists, so anything it could
 * decide per request — who may be worn, for how long, in which mode — has to be settled once, at the
 * moment the session is authorized, and written into the row the filter later reads.
 *
 * <p>The three lifecycle events go to the {@code audit_log} port. There is no {@code _expired} event
 * and no sweep job to raise one: expiry is evaluated on read
 * ({@link ImpersonationSession#isActive}), so a lapsed session simply stops resolving.
 */
@Service
class ImpersonationService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final int REASON_MIN = 8;
    private static final int REASON_MAX = 500; // impersonation_session.reason
    private static final String TARGET_POINTER = "/data/attributes/targetSubject";
    private static final String REASON_POINTER = "/data/attributes/reason";
    private static final String TTL_POINTER = "/data/attributes/ttl";

    private final ImpersonationSessionRepository sessions;
    private final UserRepository users;
    private final KeycloakUserAdminGateway keycloak;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLog auditLog;
    private final TransactionTemplate transactionTemplate;
    private final ImpersonationProperties properties;
    private final Clock clock;

    ImpersonationService(ImpersonationSessionRepository sessions, UserRepository users,
            KeycloakUserAdminGateway keycloak, CurrentUserProvider currentUserProvider, AuditLog auditLog,
            TransactionTemplate transactionTemplate, ImpersonationProperties properties, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.keycloak = keycloak;
        this.currentUserProvider = currentUserProvider;
        this.auditLog = auditLog;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Authorizes a session, cheapest check first and the one remote round-trip last. NOT
     * {@code @Transactional}: {@link #requireTierPermits} calls Keycloak, and a remote call inside the
     * local transaction would pin a connection for the length of someone else's outage. Nothing before
     * the write leaves any local state, so a failed round-trip leaves nothing to clean up.
     *
     * <p>{@code orgId} is recorded, not checked against the target's memberships: it only becomes the
     * session's active org, and permissions still resolve from the database for the target. An org the
     * target does not belong to therefore grants exactly nothing — the same fail-closed answer a
     * membership check would produce, without a second copy of the org model living here.
     */
    ImpersonationSession open(String targetSubject, UUID orgId, String reason, ImpersonationMode mode,
            Duration ttl) {
        CurrentUser caller = requireCaller();
        String actor = caller.subject();
        String target = requireImpersonableSubject(targetSubject, actor);
        String justification = requireReason(reason);
        Duration lifetime = requireTtl(ttl);
        ImpersonationMode requested = mode == null ? ImpersonationMode.READ_ONLY : mode;
        // The annotation on the endpoint can only see the tier that may open a session at all; the mode
        // is in the body, so the tier that may open a WRITE one is checked here or nowhere.
        if (requested.writeCapable() && !caller.hasRole(PlatformRole.ADMIN)) {
            throw new ForbiddenException("A write-capable impersonation session requires platform-admin.");
        }
        requireProvisionedTarget(target);
        requireTierPermits(caller, target);

        Instant now = clock.instant();
        // Explicit template, not @Transactional: this is a self-invocation from a non-transactional
        // method, which never reaches the proxy — the same reason MemberService opens its writes this way.
        return transactionTemplate.execute(
                tx -> record(actor, target, orgId, justification, requested, now, now.plus(lifetime)));
    }

    /**
     * Sessions held by one operator, live and historical, newest first — the caller's own unless they
     * name another and hold platform-admin.
     *
     * <p>That second case exists because {@link #end} already admits a platform admin, and without a way
     * to <em>find</em> another operator's session ids the branch is unreachable in practice: cutting an
     * oversight short would mean reading a uuid out of an audit detail string. Reserved to admin, not
     * open to support, because who is being investigated is itself sensitive.
     */
    @Transactional(readOnly = true)
    Window<ImpersonationSession> list(String actorSubject, CursorPageRequest page) {
        String actor = requireListableActor(requireCaller(), actorSubject);
        return sessions.findBy(
                (root, query, cb) -> cb.equal(root.get("actorSubject"), actor),
                query -> query.limit(page.size()).sortBy(NEWEST_FIRST).scroll(page.scrollPosition(NEWEST_FIRST)));
    }

    private static String requireListableActor(CurrentUser caller, String requested) {
        if (requested == null || requested.isBlank()) {
            return caller.subject();
        }
        String actor = requested.trim();
        if (!actor.equals(caller.subject()) && !caller.hasRole(PlatformRole.ADMIN)) {
            throw new ForbiddenException("Only a platform admin may review another operator's sessions.");
        }
        return actor;
    }

    /**
     * Ends a session immediately — the next request carrying its id resolves to nothing. Open to the
     * operator who holds it and to any platform admin, because the second case is how an oversight gets
     * cut short by someone other than the person being overseen.
     */
    @Transactional
    void end(UUID id) {
        CurrentUser caller = requireCaller();
        // Resolved per caller: to a non-admin, an existing-but-not-yours id answers exactly like an
        // unknown one — the repository's doctrine (a leaked session id must be worthless to anyone
        // but its operator) applies to probing this endpoint too, and a 403-vs-404 split would leak
        // which ids exist. Admins resolve by id alone: ending others' sessions is their job.
        ImpersonationSession session = (caller.hasRole(PlatformRole.ADMIN)
                ? sessions.findById(id)
                : sessions.findByIdAndActorSubject(id, caller.subject()))
                .orElseThrow(() -> new NotFoundException("Impersonation session not found."));
        if (!session.end(caller.subject(), clock.instant())) {
            return; // already ended or superseded — re-ending changes nothing and must not re-audit
        }
        sessions.save(session);
        // Null orgId for the same reason the other two lifecycle rows carry one — see record().
        auditLog.record("platform.impersonation_ended", null, session.getTargetSubject(),
                "ACTIVE", "session=" + session.getId() + " endedBy=" + caller.subject());
    }

    /**
     * The write, in one transaction so the session and its audit rows commit together. Re-issuing
     * against the same target SUPERSEDES whatever the actor already held rather than adding a second
     * live session: two live sessions for one pair would give the same reach under two different stated
     * reasons, and a review could not tell which reason covered which action. The pair is locked before
     * the read because that invariant is a read-then-insert, and an unserialised one is not an invariant
     * at all — see {@link ImpersonationSessionRepository#lockPair}.
     *
     * <p>The three lifecycle rows are recorded with a null {@code orgId}, and the requested org is kept
     * in the detail instead. Opening a session is a PLATFORM act, and {@code orgId} arrives unvalidated
     * from the operator: writing it to {@code audit_log.org_id} would let anyone holding the lowest
     * operator tier post chosen text into any tenant's {@code GET /orgs/{id}/audit} feed, about a user
     * with no connection to that tenant. What a session actually did inside an org still surfaces there,
     * on the rows for those actions, carrying {@code on_behalf_of} and {@code impersonation_id}.
     */
    private ImpersonationSession record(String actor, String target, UUID orgId, String reason,
            ImpersonationMode mode, Instant now, Instant expiresAt) {
        sessions.lockPair(actor + ":" + target);
        List<ImpersonationSession> previous =
                sessions.findByActorSubjectAndTargetSubjectAndEndedAtIsNullAndExpiresAtAfter(actor, target, now);
        ImpersonationSession session = sessions.save(
                ImpersonationSession.open(actor, target, orgId, reason, mode, now, expiresAt));
        auditLog.record("platform.impersonation_started", null, target, null,
                "session=" + session.getId() + " org=" + orgId + " mode=" + mode + " expires=" + expiresAt
                        + " reason=" + reason);
        for (ImpersonationSession superseded : previous) {
            if (superseded.end(actor, now)) {
                sessions.save(superseded);
                auditLog.record("platform.impersonation_superseded", null, target, "ACTIVE",
                        "session=" + superseded.getId() + " replacedBy=" + session.getId());
            }
        }
        return session;
    }

    private CurrentUser requireCaller() {
        return currentUserProvider.requireCurrentUser();
    }

    private static String requireImpersonableSubject(String targetSubject, String actor) {
        String target = targetSubject == null ? "" : targetSubject.trim();
        if (target.isEmpty()) {
            throw new ValidationException("targetSubject is required.", ApiSource.pointer(TARGET_POINTER));
        }
        // Self-impersonation grants nothing (the swapped principal holds no platform role) but it does
        // produce audit rows whose actor and on_behalf_of are the same person — a trail that reads as
        // oversight and records none.
        if (target.equals(actor)) {
            throw new ValidationException("You cannot impersonate yourself.", ApiSource.pointer(TARGET_POINTER));
        }
        return target;
    }

    /** The reason is the whole justification a review ever sees; a blank or one-word one is not that. */
    private static String requireReason(String reason) {
        String stated = reason == null ? "" : reason.trim();
        if (stated.length() < REASON_MIN) {
            throw new ValidationException(
                    "reason is required and must be at least " + REASON_MIN + " characters — it is the "
                            + "justification kept with the session and its audit trail.",
                    ApiSource.pointer(REASON_POINTER));
        }
        if (stated.length() > REASON_MAX) {
            // Checked rather than truncated: the stored reason must be the one the operator wrote, and a
            // silent cut is how a justification loses the sentence that mattered.
            throw new ValidationException("reason must be at most " + REASON_MAX + " characters.",
                    ApiSource.pointer(REASON_POINTER));
        }
        return stated;
    }

    private Duration requireTtl(Duration ttl) {
        if (ttl == null) {
            return properties.defaultTtl();
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new ValidationException("ttl must be a positive duration.", ApiSource.pointer(TTL_POINTER));
        }
        if (ttl.compareTo(properties.maxTtl()) > 0) {
            throw new ValidationException("ttl must not exceed " + properties.maxTtl() + ".",
                    ApiSource.pointer(TTL_POINTER));
        }
        return ttl;
    }

    /**
     * A soft-deleted account is invisible to every JPA query ({@code @SQLRestriction}), so "deleted" and
     * "never existed" arrive here as the same absence and are deliberately separated: a 404 invites the
     * operator to fix what looks like a typo — or to re-provision the very account somebody erased —
     * while a 409 says the account exists and is out of reach on purpose.
     *
     * <p>DISABLED is refused for the same reason it is refused everywhere: an account with no access of
     * its own must not become reachable through someone else's session.
     */
    private void requireProvisionedTarget(String target) {
        User user = users.findBySubject(target).orElse(null);
        if (user == null) {
            if (users.existsDeletedBySubject(target)) {
                throw new ConflictException("That account has been deleted and cannot be impersonated.");
            }
            throw new NotFoundException("No provisioned user with that subject.");
        }
        if (user.getStatus() == ProvisioningStatus.DISABLED) {
            throw new ConflictException("That account is disabled and cannot be impersonated.");
        }
    }

    /**
     * Wearing another operator's identity is an escalation path — their org memberships, their reach —
     * so it is reserved to the top tier. Superadmin short-circuits before the Keycloak round-trip: the
     * answer cannot change the outcome, and the check runs on every session that is opened.
     */
    private void requireTierPermits(CurrentUser caller, String target) {
        if (caller.hasRole(PlatformRole.SUPERADMIN)) {
            return;
        }
        Set<String> realmRoles = keycloak.realmRoles(target); // remote: OUTSIDE any local transaction
        if (realmRoles.stream().anyMatch(PlatformRole::isPlatformRole)) {
            throw new ForbiddenException(
                    "Only a platform superadmin may impersonate an account that holds a platform role.");
        }
    }
}
