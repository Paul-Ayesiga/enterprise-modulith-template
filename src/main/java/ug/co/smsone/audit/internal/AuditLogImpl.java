package ug.co.smsone.audit.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.tenancy.SplitTables;

/**
 * Writes the audit row. Fills in <em>who</em> from the security context as a {@code person.id} — null
 * when no human is answerable (a job, the dev bootstrap, startup reconciliation, an API key) — and
 * <em>when</em> from the clock; the caller supplies the rest. Runs in the caller's transaction, so the
 * row commits (or rolls back) with the change.
 *
 * <h2>Which schema the row lands in (ADR 0010 §2, {@code audit_log} is P+T)</h2>
 *
 * <p><strong>{@code org_id} decides, and nothing else does.</strong> A non-null org means the row is
 * that tenant's compliance record and must travel with it when the tenant is extracted; a null org
 * means a PLATFORM-LEVEL event — impersonation lifecycle, platform key mint and revoke, a settings or
 * feature-flag change — and those stay, because a support investigation cannot fan out across tenants
 * to reconstruct itself.
 *
 * <p><strong>Why this is the one split table whose write is schema-QUALIFIED rather than axis-routed.</strong>
 * Every other split table is reached unqualified and the {@code search_path} picks the home, which is
 * the form that keeps working when a tenant is promoted to its own schema. That is not available here.
 * {@link AuditLog#record} is called from inside the caller's transaction — that atomicity is the port's
 * whole contract — and the caller is very often NOT on the axis of the org it is recording against:
 * an operator suspending an organization, {@code SignupService} completing a signup that just created
 * one, {@code TrialExpiryJob} lapsing a subscription, the gateway posting an edge denial it made for a
 * tenant in another process. {@code TenantContext.set} throws inside an active transaction, on purpose
 * — the connection is already bound, so the switch would silently do nothing — so an axis-routed write
 * would put every one of those rows in {@code platform.audit_log} with a non-null {@code org_id}: a row
 * whose org disagrees with its schema, which ADR 0010 §1 names as the worst failure this design can
 * produce, and which is invisible until someone extracts a tenant and finds its history missing.
 *
 * <p>Naming the schema is what keeps <em>callers unchanged</em>, which was the point of
 * {@code record(action, orgId, …)} taking the org as an argument in the first place. The cost is that
 * {@link SplitTables#homeOf} has to know where a tenant lives, and today it assumes the pool — see its
 * Phase 5 note.
 *
 * <p><strong>Why JDBC and not the repository.</strong> A JPA entity carries exactly one table mapping,
 * so routing through {@link AuditEntryRepository} would need a second entity per home and would still
 * be unable to name a promoted tenant's schema. The insert joins the caller's transaction the same way
 * {@code SearchIndexStore}'s does (Spring binds one connection per transaction and {@code JdbcTemplate}
 * borrows it through {@code DataSourceUtils}), so the atomicity guarantee is unchanged. The table is
 * append-only — V17 deliberately left it out of the soft-delete sweep — so there is no update path,
 * no dirty checking and no optimistic-lock generation to lose: {@code version} is 0 and stays 0.
 * {@link AuditEntry} remains the mapping the READ side uses.
 */
@Component
@Primary
class AuditLogImpl implements AuditLog {

    private static final int TARGET_MAX = 320;
    private static final int STATE_MAX = 1000;

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    AuditLogImpl(JdbcTemplate jdbc, CurrentUserProvider currentUser, Clock clock) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Override
    public void record(String action, UUID orgId, String target, String fromState, String toState) {
        insert(orgId, action, attribution(), target, fromState, toState);
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
        insert(orgId, action, AuditEntry.Attribution.NOBODY, target, fromState,
                withEdgePrincipal(edgePrincipal, toState));
    }

    /**
     * One statement, one schema, chosen by {@code org_id} — the routing rule made mechanical rather
     * than left to each of the fifty call sites to observe.
     *
     * <p>The schema is interpolated because a schema name cannot be a bind parameter. It never comes
     * from a caller: {@link SplitTables#homeOf} answers with one of two compiled-in constants, and when
     * Phase 5 makes it answer with a silo name that name will have been through
     * {@code TenantSchemas.requireSiloSchema}. Everything else here is bound.
     */
    private void insert(UUID orgId, String action, AuditEntry.Attribution attribution, String target,
            String fromState, String toState) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("insert into " + SplitTables.homeOf(orgId) + ".audit_log ("
                        + "id, org_id, action, actor_person_id, on_behalf_of_person_id, impersonation_id, "
                        + "target, from_state, to_state, occurred_at, version, created_at, created_by) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                UUID.randomUUID(), orgId, action,
                attribution.actorPersonId(), attribution.onBehalfOfPersonId(), attribution.impersonationId(),
                truncate(target, TARGET_MAX), truncate(fromState, STATE_MAX), truncate(toState, STATE_MAX),
                // occurred_at and created_at are both the clock's instant, as they were: the port
                // records at the point of change, so "when it happened" and "when it was written" are
                // the same instant here and the two columns only diverge for a backdated import.
                now, now,
                // created_by keeps the meaning JpaAuditingConfig gave it and NOT the accountable actor:
                // the EFFECTIVE person, so an impersonated write reads like the target's own everywhere
                // except actor_person_id, which is the one column that exists to name the operator.
                currentUser.currentPersonId().orElse(null));
    }

    private static String withEdgePrincipal(String edgePrincipal, String toState) {
        if (edgePrincipal == null || edgePrincipal.isBlank()) {
            return toState;
        }
        String stated = "edgePrincipal=" + edgePrincipal.trim();
        return toState == null || toState.isBlank() ? stated : stated + " " + toState;
    }

    /**
     * V13 sizes {@code target} and the two state columns, and a caller that overruns one would take
     * down the change it was recording. Truncation lived in {@code AuditEntry.of} while the row went
     * through JPA; it moves here with the write.
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
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
