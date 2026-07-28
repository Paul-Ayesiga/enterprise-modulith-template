package ug.co.smsone.audit.internal;

import org.springframework.stereotype.Component;
import ug.co.smsone.shared.events.EventInbox;

/**
 * Persists an audit row, de-duplicated via {@link EventInbox}: domain-event delivery is at-least-once,
 * and an audit log must be exact, so a redelivered event whose message id was already recorded is
 * skipped. Runs inside the listener's transaction, so the inbox record and the row commit together.
 */
@Component
class AuditRecorder {

    private static final String LISTENER_ID = "audit";

    private final AuditEntryRepository repository;
    private final EventInbox inbox;

    AuditRecorder(AuditEntryRepository repository, EventInbox inbox) {
        this.repository = repository;
        this.inbox = inbox;
    }

    void record(String messageId, AuditEntry entry) {
        if (inbox.recordIfNew(LISTENER_ID, messageId)) {
            repository.save(entry);
        }
    }
}
