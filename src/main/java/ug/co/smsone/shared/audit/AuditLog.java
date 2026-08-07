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

    /**
     * Record an event NO PERSON performed, naming the acting principal as an explicit parameter.
     *
     * <p>The rule is <strong>the actor is a parameter ONLY where no person acted at all</strong> — so
     * there is nothing for the security context to misstate. It is NOT "edge events only", which is how
     * this was first written and which forbids the second case it is exactly right for. Wherever a
     * person IS on the thread, {@link #record} is the only correct choice and stays mandatory: it alone
     * knows that a write inside an impersonation session is answerable to the operator rather than to
     * the identity the request runs as, and an actor every call site has to remember to pass is an
     * actor some call site will pass wrongly.
     *
     * <p>The two legitimate callers:
     * <ul>
     *   <li>{@code audit.GatewayAuditController} — the gateway resolved the principal in ANOTHER
     *       process and posts the decision here; this thread has no security context to consult.</li>
     *   <li>{@code mcp.McpToolDispatcher} — an MCP mutation made by a machine credential. A security
     *       context exists but holds no person (a robot is not one), so {@code record} would resolve to
     *       NOBODY and the row would say only that something changed the tenant.</li>
     * </ul>
     *
     * <p>Either way {@code actor_person_id} stays NULL — the truthful answer for a non-person actor,
     * not a gap — and the principal travels as text prefixed {@code edgePrincipal=} on {@code toState}.
     * The impl says why a uuid is never minted to fill the column instead.
     *
     * @param actor the principal that acted where no person did — a token subject, {@code key:<id>},
     *              or {@code service:<name>}; null if none could be resolved
     */
    void recordExternal(String action, UUID orgId, String actor, String target, String fromState, String toState);
}
