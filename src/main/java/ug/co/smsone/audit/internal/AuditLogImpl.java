package ug.co.smsone.audit.internal;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;

/**
 * Writes the audit row. Fills in <em>who</em> from the security context as a {@code person.id} — null
 * when no human is answerable (a job, the dev bootstrap, startup reconciliation, an API key) — and
 * <em>when</em> from the clock; the caller supplies the rest. Runs in the caller's transaction, so the
 * row commits (or rolls back) with the change.
 */
@Component
@Primary
class AuditLogImpl implements AuditLog {

    private final AuditEntryRepository repository;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    AuditLogImpl(AuditEntryRepository repository, CurrentUserProvider currentUser, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Override
    public void record(String action, UUID orgId, String target, String fromState, String toState) {
        repository.save(AuditEntry.of(orgId, action, attribution(), target, fromState, toState, clock.instant()));
    }

    /**
     * The edge principal arrives as a STRING FROM ANOTHER PROCESS — a token subject, a key id, a
     * service name — and {@code actor_person_id} holds a {@code person.id} (V13). This process cannot
     * turn one into the other (it has no issuer to resolve against, and two of the three shapes are not
     * people at all), and minting something uuid-shaped to fill the column would put a fabricated human
     * in the one table whose job is to say who acted.
     *
     * <p>So the row is attributed to NOBODY — which V13 states is the honest answer for a system or
     * machine actor — and the principal travels as text beside the outcome it describes. Nothing is
     * lost; it stops claiming to be an identity this platform owns.
     */
    @Override
    public void recordExternal(String action, UUID orgId, String edgePrincipal, String target,
            String fromState, String toState) {
        repository.save(AuditEntry.of(orgId, action, AuditEntry.Attribution.NOBODY,
                target, fromState, withEdgePrincipal(edgePrincipal, toState), clock.instant()));
    }

    private static String withEdgePrincipal(String edgePrincipal, String toState) {
        if (edgePrincipal == null || edgePrincipal.isBlank()) {
            return toState;
        }
        String stated = "edgePrincipal=" + edgePrincipal.trim();
        return toState == null || toState.isBlank() ? stated : stated + " " + toState;
    }

    /**
     * Read from the security context here rather than taken as a port parameter: an argument every call
     * site has to remember is an argument some call site will forget, and this is the one table that
     * must never be wrong about who acted. It is also why {@link AuditLog}'s signature stays unchanged.
     *
     * <p>Inside an impersonation session the attribution INVERTS relative to the request's effective
     * identity: {@code actorPersonId} becomes the operator who opened the session — the human
     * answerable — and the target moves to {@code onBehalfOfPersonId}. Everywhere else in that request
     * the effective principal <em>is</em> the target (rate-limit bucket, idempotency key,
     * {@code created_by}), which is exactly why this row has to say something different from all of them.
     *
     * <p>A MACHINE lands on {@code NOBODY} through the same path a job does, and that is correct rather
     * than a gap: {@link CurrentUser#accountablePersonId()} is null for an API key because no human is
     * answerable for a robot, and V13 chose a null over a synthetic person for exactly this row. What
     * the key did is still in {@code action}/{@code target}; what it is called is in {@code api_key}.
     */
    private AuditEntry.Attribution attribution() {
        CurrentUser user = currentUser.currentUser().orElse(null);
        if (user == null || user.accountablePersonId() == null) {
            return AuditEntry.Attribution.NOBODY;
        }
        if (!user.isImpersonated()) {
            return new AuditEntry.Attribution(user.personId(), null, null);
        }
        return new AuditEntry.Attribution(
                user.accountablePersonId(), user.personId(), user.impersonation().sessionId());
    }
}
