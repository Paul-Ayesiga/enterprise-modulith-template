package ug.co.smsone.audit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.shared.persistence.BaseEntity;

/**
 * One append-only audit record: <em>who</em> ({@code actor}) did <em>what</em> ({@code action}) to
 * <em>which thing</em> ({@code target}) <em>where</em> ({@code orgId}) <em>when</em> ({@code occurredAt}),
 * and the before/after ({@code fromState}/{@code toState}).
 */
@Entity
@Table(name = "audit_log")
class AuditEntry extends BaseEntity {

    private static final int TARGET_MAX = 320;
    private static final int STATE_MAX = 1000;

    @Column(name = "org_id")
    private UUID orgId; // null for platform-level (non-org) events

    @Column(nullable = false, length = 80)
    private String action;

    @Column(length = 64)
    private String actor; // acting principal's subject; null for system-triggered changes

    @Column(length = TARGET_MAX)
    private String target;

    @Column(name = "from_state", length = STATE_MAX)
    private String fromState;

    @Column(name = "to_state", length = STATE_MAX)
    private String toState;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEntry() {
        // JPA
    }

    static AuditEntry of(UUID orgId, String action, String actor, String target,
            String fromState, String toState, Instant occurredAt) {
        AuditEntry entry = new AuditEntry();
        entry.orgId = orgId;
        entry.action = action;
        entry.actor = truncate(actor, 64);
        entry.target = truncate(target, TARGET_MAX);
        entry.fromState = truncate(fromState, STATE_MAX);
        entry.toState = truncate(toState, STATE_MAX);
        entry.occurredAt = occurredAt;
        return entry;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    UUID getOrgId() {
        return orgId;
    }

    String getAction() {
        return action;
    }

    String getActor() {
        return actor;
    }

    String getTarget() {
        return target;
    }

    String getFromState() {
        return fromState;
    }

    String getToState() {
        return toState;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
