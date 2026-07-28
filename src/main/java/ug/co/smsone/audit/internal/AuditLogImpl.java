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
        String actor = currentUser.currentUser().map(CurrentUser::subject).orElse(null);
        repository.save(AuditEntry.of(orgId, action, actor, target, fromState, toState, clock.instant()));
    }
}
