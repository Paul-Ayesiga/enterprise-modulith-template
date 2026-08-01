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
 *
 * <p>Under impersonation the <em>who</em> splits in two — see {@link Attribution}.
 */
@Entity
@Table(name = "audit_log")
class AuditEntry extends BaseEntity {

    private static final int TARGET_MAX = 320;
    private static final int STATE_MAX = 1000;
    private static final int SUBJECT_MAX = 64;

    /**
     * The <em>who</em> of a row: the human answerable ({@code actor}), and — only inside an
     * impersonation session — the identity they were wearing ({@code onBehalfOf}) plus the session that
     * carries the stated reason ({@code impersonationId}).
     *
     * <p>Grouped rather than passed as two adjacent {@code String} parameters: swapping those compiles
     * cleanly and silently inverts the one fact this table exists to state.
     */
    record Attribution(String actor, String onBehalfOf, UUID impersonationId) {

        /** No principal on the thread — a system-triggered change (jobs, startup reconciliation). */
        static final Attribution SYSTEM = new Attribution(null, null, null);
    }

    @Column(name = "org_id")
    private UUID orgId; // null for platform-level (non-org) events

    @Column(nullable = false, length = 80)
    private String action;

    @Column(length = SUBJECT_MAX)
    private String actor; // accountable principal's subject; null for system-triggered changes

    @Column(name = "on_behalf_of", length = SUBJECT_MAX)
    private String onBehalfOf; // the impersonated subject; null unless `actor` acted through a session

    @Column(name = "impersonation_id")
    private UUID impersonationId; // soft ref to impersonation_session — no FK across a module boundary

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

    static AuditEntry of(UUID orgId, String action, Attribution attribution, String target,
            String fromState, String toState, Instant occurredAt) {
        AuditEntry entry = new AuditEntry();
        entry.orgId = orgId;
        entry.action = action;
        entry.actor = truncate(attribution.actor(), SUBJECT_MAX);
        entry.onBehalfOf = truncate(attribution.onBehalfOf(), SUBJECT_MAX);
        entry.impersonationId = attribution.impersonationId();
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

    String getOnBehalfOf() {
        return onBehalfOf;
    }

    UUID getImpersonationId() {
        return impersonationId;
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
