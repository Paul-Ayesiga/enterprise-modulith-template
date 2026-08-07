package ug.co.smsone.audit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.shared.persistence.BaseEntity;

/**
 * One append-only audit record: <em>who</em> ({@code actorPersonId}) did <em>what</em> ({@code action})
 * to <em>which thing</em> ({@code target}) <em>where</em> ({@code orgId}) <em>when</em>
 * ({@code occurredAt}), and the before/after ({@code fromState}/{@code toState}).
 *
 * <p>Under impersonation the <em>who</em> splits in two — see {@link Attribution}.
 *
 * <p><b>The who is a {@code person.id}; the what-it-was-done-to is text.</b> That asymmetry is the
 * schema's (V13) and it is deliberate: an actor is always one species of thing, so it gets a typed
 * column, while {@code target} is polymorphic and read through {@code action} — a person id here, a
 * ticket uuid there, a setting key elsewhere — so typing it would force every writer to choose between
 * two columns on a contract encoded in a string.
 */
@Entity
@Table(name = "audit_log")
class AuditEntry extends BaseEntity {

    private static final int TARGET_MAX = 320;
    private static final int STATE_MAX = 1000;

    /**
     * The <em>who</em> of a row: the person answerable ({@code actorPersonId}), and — only inside an
     * impersonation session — the identity they were wearing ({@code onBehalfOfPersonId}) plus the
     * session that carries the stated reason ({@code impersonationId}).
     *
     * <p>Grouped rather than passed as two adjacent {@code UUID} parameters: swapping those compiles
     * cleanly and silently inverts the one fact this table exists to state. Three same-typed ids
     * side by side is a stronger argument for the record than the two strings ever were.
     */
    record Attribution(UUID actorPersonId, UUID onBehalfOfPersonId, UUID impersonationId) {

        /**
         * Nobody accountable: a system-triggered change (jobs, startup reconciliation), a machine key,
         * or an edge decision made in another process. V13 says that is information rather than a
         * missing value, and telling those three apart needs a typed actor triple, not a widened column.
         */
        static final Attribution NOBODY = new Attribution(null, null, null);
    }

    @Column(name = "org_id")
    private UUID orgId; // organization.id; null for platform-level (non-org) events

    @Column(nullable = false, length = 80)
    private String action;

    @Column(name = "actor_person_id")
    private UUID actorPersonId; // the accountable human; null = system or machine

    @Column(name = "on_behalf_of_person_id")
    private UUID onBehalfOfPersonId; // the worn identity; null unless the actor acted through a session

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
        entry.actorPersonId = attribution.actorPersonId();
        entry.onBehalfOfPersonId = attribution.onBehalfOfPersonId();
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

    UUID getActorPersonId() {
        return actorPersonId;
    }

    UUID getOnBehalfOfPersonId() {
        return onBehalfOfPersonId;
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
