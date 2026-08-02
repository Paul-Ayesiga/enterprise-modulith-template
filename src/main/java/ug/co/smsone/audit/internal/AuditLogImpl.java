package ug.co.smsone.audit.internal;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;

/**
 * Writes the audit row. Fills in <em>who</em> from the security context (null for system-triggered
 * changes — dev bootstrap, startup reconciliation) and <em>when</em> from the clock; the caller supplies
 * the rest. Runs in the caller's transaction, so the row commits (or rolls back) with the change.
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

    @Override
    public void recordExternal(String action, UUID orgId, String actor, String target,
            String fromState, String toState) {
        // The actor came over the wire (the edge resolved it), not from this thread's context.
        repository.save(AuditEntry.of(orgId, action, new AuditEntry.Attribution(actor, null, null),
                target, fromState, toState, clock.instant()));
    }

    /**
     * Read from the security context here rather than taken as a port parameter: an argument every call
     * site has to remember is an argument some call site will forget, and this is the one table that
     * must never be wrong about who acted. It is also why {@link AuditLog}'s signature stays unchanged.
     *
     * <p>Inside an impersonation session the attribution INVERTS relative to the request's effective
     * identity: {@code actor} becomes the operator who opened the session — the human answerable — and
     * the target moves to {@code onBehalfOf}. Everywhere else in that request the effective principal
     * <em>is</em> the target (rate-limit bucket, idempotency key, {@code created_by}), which is exactly
     * why this row has to say something different from all of them.
     */
    private AuditEntry.Attribution attribution() {
        CurrentUser user = currentUser.currentUser().orElse(null);
        if (user == null) {
            // Null, not the "system" sentinel the JPA auditing columns use: an audit row with no actor is
            // a system-triggered change, and inventing a pseudo-principal for it would be a lie in the one
            // table whose job is to say who did what.
            return AuditEntry.Attribution.SYSTEM;
        }
        if (!user.isImpersonated()) {
            return new AuditEntry.Attribution(user.subject(), null, null);
        }
        return new AuditEntry.Attribution(
                user.accountableSubject(), user.subject(), user.impersonation().sessionId());
    }
}
