package ug.co.smsone.shared.tenancy.promotion;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Every statement against {@code platform.tenant_freeze} (V58), and the one question background work
 * asks of it: <strong>may I write this tenant's rows right now?</strong>
 *
 * <h2>The hazard, stated once</h2>
 *
 * <p>ADR 0010 §6 hop 0→1 names it explicitly: the promotion freeze is an org-scoped
 * {@code maintenance_window} in RESTRICT mode, "<em>but that gates HTTP org paths only; the
 * notification worker, webhook dispatcher, exchange runners, {@code OutboxResubmissionJob} and
 * retention purges all write tenant rows OUTSIDE any request and must be paused for that org too</em>".
 * A promoter that took only the HTTP freeze would copy rows out from under a worker still writing
 * them, and the row-count/checksum verification that follows would then pass or fail on timing —
 * which is the one thing a verification step must never do.
 *
 * <p>So there are two freezes and they are enforced in different places:
 *
 * <ul>
 *   <li><strong>HTTP</strong> — {@code maintenance_window}, RESTRICT, read by {@code MaintenanceFilter}
 *       at {@code @Order(4)} on every org write. Opened through {@link HttpWriteFreeze}.</li>
 *   <li><strong>Everything else</strong> — this table. {@code QueueSignals.claim} will not hand a
 *       frozen tenant to any of the three durable queues, and every per-tenant job asks
 *       {@link #isFrozen(UUID)} before it enters a tenant.</li>
 * </ul>
 *
 * <h2>A freeze expires, and that is not a convenience</h2>
 *
 * <p>The promoter is a process, and processes die. A freeze with no deadline taken by a pod that is
 * then evicted stops that tenant's webhooks, exchanges, escalations and retention <em>forever</em>,
 * with nothing in the system able to tell the difference between "a promotion is running" and "a
 * promotion died in 2026". So every row carries {@code expires_at}, every read here compares against
 * it in SQL, and an expired freeze is <strong>not a freeze</strong>. The bound is
 * {@code app.tenancy.promotion.freeze}, and the runbook tells the operator to size it against the
 * tenant, not against taste.
 *
 * <p>The consequence is the honest one and it is written into the promoter: a copy that outruns its
 * own freeze is an <em>aborted</em> promotion, not one that silently extends the pause. The promoter
 * checks the deadline is still ahead of it before every step that matters, and the last of those
 * checks sits immediately before the placement flip.
 *
 * <h2>Not a lock</h2>
 *
 * <p>Two promotions of the same tenant are made impossible by {@code TenantPlacements}' conditional
 * writes, not by this table — {@link #freeze} is an upsert and will happily replace a stale row, which
 * is what makes a retry after a crashed promotion a retry rather than a permanent refusal. Read this
 * table as "is a promotion in progress for this tenant", never as "have I acquired the right to run
 * one".
 */
@Component
public class TenantFreezes {

    private final JdbcTemplate jdbc;

    TenantFreezes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Freezes {@code orgId}'s background work for {@code budget}, replacing any freeze already there.
     *
     * <p>The deadline is computed <strong>in SQL</strong> ({@code now() + interval}) rather than from
     * the JVM's clock, for the reason {@code QueueSignals.raise} gives about the same choice: the
     * readers all compare against the database's {@code now()}, so minting the deadline anywhere else
     * puts clock skew between the freeze and the thing that decides whether it is still in force —
     * and skew in the wrong direction is a thaw nobody asked for, in the middle of a copy.
     *
     * @param holder which process took it, for the human who finds the row
     * @return when the freeze lapses on its own if nothing thaws it first
     */
    public Instant freeze(UUID orgId, String reason, String holder, Duration budget) {
        if (orgId == null || reason == null || reason.isBlank() || holder == null || holder.isBlank()) {
            throw new IllegalArgumentException(
                    "a freeze names the tenant, the reason and the process holding it — a row missing any"
                            + " of the three is one nobody can act on at 03:00");
        }
        if (budget == null || budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException(
                    "a freeze needs a positive deadline: a freeze that never expires is one a dead promoter"
                            + " leaves behind forever (ADR 0010 §6 hop 0->1), got " + budget);
        }
        return jdbc.queryForObject("""
                insert into platform.tenant_freeze (org_id, reason, frozen_at, expires_at, holder)
                values (?, ?, now(), now() + (? * interval '1 millisecond'), ?)
                on conflict (org_id) do update
                   set reason = excluded.reason,
                       frozen_at = excluded.frozen_at,
                       expires_at = excluded.expires_at,
                       holder = excluded.holder
                returning expires_at
                """,
                (rs, row) -> rs.getTimestamp("expires_at").toInstant(),
                orgId, reason, budget.toMillis(), holder);
    }

    /**
     * Lifts the freeze. Idempotent, and deliberately <strong>unfenced</strong> — anyone may thaw a
     * tenant, including an operator running the statement by hand from the runbook.
     *
     * <p>Fencing this on the holder was considered and rejected: the failure it would prevent (one
     * promoter thawing another's tenant) is already impossible, because two promotions of one tenant
     * cannot both get past {@code TenantPlacements}, while the failure it would CAUSE — a tenant left
     * frozen because the only process allowed to release it is gone — is the exact outage
     * {@code expires_at} exists to bound.
     *
     * @return true if a row was there to remove
     */
    public boolean thaw(UUID orgId) {
        return jdbc.update("delete from platform.tenant_freeze where org_id = ?", orgId) > 0;
    }

    /**
     * Whether background work must stay off this tenant. <strong>Expired rows answer false</strong> —
     * see the class note.
     *
     * <p>One indexed primary-key read against a table that holds one row on the busiest day this
     * design anticipates (§8 Q3: operator-initiated promotion only). It is cheap enough to ask once
     * per tenant per job iteration and is meant to be asked exactly there — never cached, because the
     * whole value of the answer is that it is current.
     */
    public boolean isFrozen(UUID orgId) {
        if (orgId == null) {
            return false;
        }
        Boolean frozen = jdbc.queryForObject("""
                select exists (select 1 from platform.tenant_freeze
                                where org_id = ? and expires_at > now())
                """, Boolean.class, orgId);
        return Boolean.TRUE.equals(frozen);
    }

    /**
     * The guard a per-tenant job calls before it enters a tenant, and the shape it is in because a
     * boolean nobody checks is not a guard.
     *
     * <p>Callers that can skip and come back — the fan-out jobs, whose next pass is minutes away —
     * should use {@link #isFrozen(UUID)} and simply not visit this tenant on this pass. This throwing
     * form is for the paths where continuing would be a silent write into a schema that is being moved
     * out from under them.
     *
     * @throws TenantFrozenException always, when a promotion holds this tenant
     */
    public void requireWritable(UUID orgId) {
        if (isFrozen(orgId)) {
            throw new TenantFrozenException(orgId);
        }
    }

    /**
     * Every freeze currently in force, oldest first — "is anything holding a tenant right now, and
     * since when". The operator's read, and the runbook's first diagnostic.
     */
    public List<Freeze> inForce() {
        return jdbc.query("""
                select org_id, reason, frozen_at, expires_at, holder
                  from platform.tenant_freeze
                 where expires_at > now()
                 order by frozen_at
                """,
                (rs, row) -> new Freeze(
                        rs.getObject("org_id", UUID.class),
                        rs.getString("reason"),
                        rs.getTimestamp("frozen_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("holder")));
    }

    /** One row of {@code platform.tenant_freeze}. */
    public record Freeze(UUID orgId, String reason, Instant frozenAt, Instant expiresAt, String holder) {
    }
}
