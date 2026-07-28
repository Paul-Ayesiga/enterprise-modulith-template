package ug.co.smsone.shared.audit;

import java.util.UUID;

/**
 * Records a state change to the audit trail — the who/when/where/what/from→to of an action. A shared
 * port so any module can audit without depending on the audit module (the audit module provides the
 * impl, which fills in <em>who</em> from the security context and <em>when</em> from the clock).
 *
 * <p>Call it at the point of change, inside the changing transaction: the audit row then commits (or
 * rolls back) atomically with the change, and the acting principal is still on the thread.
 */
public interface AuditLog {

    /**
     * @param action    a dotted verb, e.g. {@code organization.member_role_changed}
     * @param orgId     the tenant the change belongs to, or {@code null} for platform-level changes
     * @param target    the affected entity (a subject, alias, role code, setting key…)
     * @param fromState prior value, or {@code null} for a creation / an action with no prior state
     * @param toState   new value, or {@code null} for a deletion
     */
    void record(String action, UUID orgId, String target, String fromState, String toState);

    /** A change with no meaningful before/after (a pure action). */
    default void record(String action, UUID orgId, String target) {
        record(action, orgId, target, null, null);
    }
}
