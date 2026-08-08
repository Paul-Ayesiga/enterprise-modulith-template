package ug.co.smsone.shared.queue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.persistence.DbDialect;

/**
 * {@code platform.queue_signal} — one row per (queue, tenant) that has unfinished work, and the
 * two-step claim built on it (ADR 0010 §2.1, Phase 3).
 *
 * <p><strong>Claim the TENANT here, then claim the ROWS on that tenant's axis.</strong> That order is
 * what the whole design turns on. A durable queue's claim is a search for work that belongs to no
 * tenant yet, so nothing can have pinned an axis before it runs — which is why the pre-signal claim
 * had to be one cluster-wide sweep, and why {@code webhook_delivery}'s join to
 * {@code webhook_subscription} (both tenant-tier) stopped being expressible the moment the schemas
 * split. Splitting the claim in two puts the pin between the halves: this class answers "which tenant
 * has work" from a platform-tier table that any axis can read, the caller pins that tenant, and the
 * existing claim statement runs unchanged but for one {@code and org_id = ?}.
 *
 * <p><strong>And it is what makes fairness structural.</strong> One signal row per tenant means one
 * claim covers exactly one tenant, so a tenant with a million queued rows holds at most one claim slot
 * and cannot fill another worker's batch. The outage {@code WebhookDeliveryQueue.claim}'s javadoc
 * narrates — one paused subscription's rows occupying every slot until NO tenant got a webhook — is
 * impossible in this shape rather than prevented by a predicate a future edit could drop.
 *
 * <p><strong>Three rules, and each one is load-bearing:</strong>
 *
 * <ul>
 *   <li><strong>Raise inside the same transaction as the rows.</strong> {@link #raise} writes an index
 *       over the queue; if it commits separately from the rows it indexes there is a window in which a
 *       worker claims the tenant, finds nothing, and deletes the signal for rows that then commit —
 *       queued work no poll will ever look at again. Every caller wraps the two writes in one
 *       transaction, and the row lock the upsert takes is also what makes a concurrent
 *       {@code SKIP LOCKED} claim skip a tenant mid-enqueue rather than see it half-written.</li>
 *   <li><strong>Never move a signal later.</strong> The upsert is {@code least(existing, excluded)}, so
 *       new work can only ever pull a tenant's turn forward. Overwriting with a later time would park
 *       whatever was already due behind the newest enqueue.</li>
 *   <li><strong>Release is fenced on the lease TOKEN.</strong> {@link #claim} mints a uuid into
 *       {@code lease} and returns it; {@link #release} only writes where that uuid is still there.
 *       Anything that took the row in between — another worker's claim after this lease expired, an
 *       enqueue that voided it — wins, and this worker's release becomes a no-op. That is the safe
 *       direction: a signal that survives costs one empty probe, a signal deleted out from under live
 *       rows costs the rows. The token is a uuid rather than the {@code due_at} the claim wrote
 *       because a timestamp cannot be compared for identity across a round trip without betting the
 *       whole fairness property on nothing rounding it — see V56.</li>
 * </ul>
 *
 * <p><strong>A stale signal is harmless and is meant to be.</strong> A worker that claims a tenant and
 * finds nothing releases it, which deletes the row when the tenant has nothing left at all. Nothing
 * else reconciles this table, and nothing needs to: it is only ever consulted to decide where to look
 * next, never to decide what is true.
 */
@Component
public class QueueSignals {

    /**
     * The scope key for rows that belong to no organization — {@code notification_delivery.org_id} is
     * genuinely nullable and {@code exchange_job.org_id} null means a platform-scoped handler (ADR 0010
     * §2 rows 27 and 10). {@code queue_signal.org_id} is part of a primary key and cannot be null, so
     * those rows key on the nil uuid, which no {@code organization.id} can ever hold: every primary key
     * in this schema is a random or time-ordered uuid.
     *
     * <p><strong>This is not the {@code new UUID(0L, 0L)} that jobs pass to {@code TenantContext}</strong>,
     * even though it is the same literal. That one names an AXIS — an org in no {@code organization}
     * row can only resolve to the pool, so it IS the pooled schema's axis spelled in the only
     * vocabulary {@code TenantContext} has. This one names a ROW SCOPE — the rows whose {@code org_id}
     * is null. They coincide in spelling because they lean on the same fact about uuids, not because
     * they mean the same thing, and a queue that confused them would pin a tenant axis to read platform
     * rows.
     */
    public static final UUID PLATFORM_SCOPE = new UUID(0L, 0L);

    private final JdbcTemplate jdbc;
    private final DbDialect dialect;

    QueueSignals(JdbcTemplate jdbc, DbDialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** The signal key for an org, mapping the org-less case onto {@link #PLATFORM_SCOPE}. */
    public static UUID scopeOf(UUID orgId) {
        return orgId == null ? PLATFORM_SCOPE : orgId;
    }

    /** True when this scope key stands for the org-less rows rather than a real tenant. */
    public static boolean isPlatformScope(UUID scope) {
        return PLATFORM_SCOPE.equals(scope);
    }

    /**
     * Records that {@code scope} has work claimable now — <strong>once per batch, never once per
     * row</strong>. A fan-out of forty thousand deliveries writes one row here, which is what keeps
     * this table an index over the queue rather than a second copy of it.
     *
     * <p>Must run in the same transaction as the rows it announces; see the class note.
     *
     * <p><strong>No caller supplies the time, and none should.</strong> Every enqueue path in the three
     * queues writes {@code next_attempt_at = now()} (or a {@code locked_at} that is already past), so
     * the only honest answer is the DATABASE's now — and taking it from the JVM would put the app
     * clock's skew between the rows and the signal that announces them. Ahead of the database, that
     * skew is a delay nothing recovers from until the next release. A signal that is due EARLIER than
     * its rows costs one empty probe; the reverse costs latency, so there is only one safe direction
     * and this is it.
     *
     * <p><strong>An enqueue VOIDS whatever lease was outstanding</strong>, and that is what protects
     * the work it just announced. A worker holding this tenant computed its next {@code due_at} from
     * the rows it could see, which are not these; letting its release land afterwards would park the
     * tenant on a time computed before the new work existed. Clearing the token makes that release a
     * no-op by the same rule that fences every other stale one, so the {@code due_at = now()} written
     * here stands and the next poll finds the rows.
     */
    public void raise(String queue, UUID scope) {
        jdbc.update("""
                insert into platform.queue_signal (queue, org_id, due_at, lease)
                values (?, ?, now(), null)
                on conflict (queue, org_id)
                do update set due_at = least(queue_signal.due_at, excluded.due_at), lease = null
                """, queue, scope);
    }

    /**
     * Takes the longest-waiting due tenant on {@code queue} and leases it for {@code lease}, or empty
     * when no tenant is due.
     *
     * <p>{@code order by … limit 1 for update skip locked} picks the candidate and the update takes it,
     * so concurrent workers step over each other's tenants instead of contending for one. The lease is
     * what makes the skip meaningful ACROSS statements — the row lock lasts only as long as this
     * autocommitted statement, and the caller then goes away to run a whole batch on another
     * connection, so the tenant has to be invisible for that stretch by its {@code due_at} rather than
     * by a lock nobody is holding.
     *
     * <p>{@code lease} should be the queue's stale-lock: a worker that dies mid-batch leaves its rows
     * reclaimable after exactly that long, so making the tenant claimable at the same moment keeps the
     * two recoveries in step instead of having one wait on the other. Both halves of that sentence are
     * now the DATABASE's clock — {@code now() + lease} is written by the same clock that
     * {@code due_at <= now()} reads, so no amount of app/database skew can shorten or stretch a lease.
     *
     * <p><b>{@code as materialized} is the load-bearing word in this statement.</b> Written the obvious
     * way — {@code update … from (select … limit 1 for update skip locked) c where s.org_id = c.org_id}
     * — the candidate is an ordinary join input, and a nested loop is free to RESCAN it once per outer
     * row. Rescanning does not return the same tenant twice: {@code LockRows} skips a row already
     * updated by the current command, so the second execution returns the SECOND due tenant, the third
     * the third, and one claim silently leases every due tenant on the queue while returning one. The
     * others are stamped with a lease no worker holds, so they vanish for the whole stale-lock window
     * and their work simply waits — the exact starvation this table exists to remove, reintroduced
     * underneath it, and visible only as a fairness test that fails when the planner happens to pick
     * that shape. It did: three of them, in a suite that passed the run before. A materialized CTE is
     * evaluated exactly once into a tuplestore, so a rescan re-reads the same single row and the
     * statement can only ever touch one tenant — a property of the plan-independent semantics rather
     * than of the plan we happened to get.
     *
     * <p><b>The three ROW claims share the idiom and not the exposure, which is worth knowing before
     * anyone "fixes" them too.</b> They join the candidate on a primary key and nothing else
     * ({@code d.id = c.id}), so putting the queue table on the outside would mean scanning all of it —
     * never the cheaper plan, so the candidate always drives and cannot be rescanned. This statement
     * differed by having a second, cheap-looking predicate on the outer relation ({@code s.queue = ?},
     * which the planner reads as about one row), and that is the whole difference: it made the signal
     * table an attractive outer relation. Checked against Postgres 18 with the statistics that provoke
     * it, on all four statements.
     *
     * <p><b>A tenant being MOVED is not handed out at all.</b> The {@code not exists} arm is the queue
     * half of a promotion freeze (ADR 0010 §6 hop 0→1): while {@code platform.tenant_freeze} holds a
     * live row for an organization, its rows are being copied between schemas, and a worker that
     * claimed it here would write into the schema the tenant is leaving — a write that is either lost
     * at the flip or aborts the promotion by moving a row count. §6 names this failure explicitly and
     * names this table as the fix: the RESTRICT maintenance window gates HTTP org paths only, and every
     * durable queue in this system claims its work outside any request.
     *
     * <p>Three properties of putting it HERE rather than in each worker: it covers all three queues at
     * once, because all three claim through this statement; it costs one primary-key probe against a
     * table that holds one row on the busiest day promotion is expected to see; and a frozen tenant's
     * {@code due_at} is left untouched, so the work is not delayed by a second — the moment the freeze
     * lapses the tenant sorts exactly where it always did. What it does NOT cover is a worker that
     * claimed the tenant just BEFORE the freeze went on and is still running its batch; that is what
     * {@code app.tenancy.promotion.drain} is sized against, and what the fingerprint comparison catches
     * when the sizing is wrong.
     *
     * @throws IncorrectResultSizeDataAccessException if more than one tenant was leased, which the
     *     statement above makes impossible — it is here so that a future rewrite that reintroduces the
     *     rescan fails loudly instead of quietly starving tenants
     */
    public Optional<Leased> claim(String queue, Duration lease) {
        UUID token = UUID.randomUUID();
        List<Leased> leased = jdbc.query("""
                with candidate as materialized (
                    select queue, org_id from platform.queue_signal
                    where queue = ? and due_at <= now()
                      and not exists (select 1 from platform.tenant_freeze f
                                       where f.org_id = queue_signal.org_id and f.expires_at > now())
                    order by due_at
                    limit 1
                    %s
                )
                update platform.queue_signal s
                set due_at = now() + (? * interval '1 millisecond'), lease = ?
                from candidate c
                where s.queue = c.queue and s.org_id = c.org_id
                returning s.org_id
                """.formatted(dialect.skipLocked()),
                (rs, rowNum) -> new Leased(rs.getObject("org_id", UUID.class), token),
                queue, lease.toMillis(), token);
        if (leased.size() > 1) {
            throw new IncorrectResultSizeDataAccessException(
                    "One claim leased " + leased.size() + " tenants on queue " + queue
                            + "; every tenant but the first is now parked behind a lease nobody holds",
                    1, leased.size());
        }
        return leased.stream().findFirst();
    }

    /**
     * Hands a leased tenant back with what it has left: {@code dueAt} is when its earliest remaining row
     * becomes claimable, or {@code null} when nothing remains at all — which deletes the signal.
     *
     * <p><strong>{@code greatest(dueAt, now())} is where round-robin comes from,</strong> and it is not
     * a rounding detail. A tenant sitting on a backlog reports an earliest-claimable time in the past,
     * every time, forever; written literally it would sort ahead of every tenant enqueued since and get
     * claimed again on the next poll, and the next, which is the starvation this whole table exists to
     * remove — just relocated from the {@code limit} to the ordering. Clamping to now() says the true
     * thing ("due immediately") in the form that also puts the tenant behind everyone who has been
     * waiting longer. {@code now()} is the DATABASE's clock, so the ordering never depends on which
     * instance released.
     *
     * <p><strong>The fence means a release can legitimately do nothing</strong> — the token moved on, so
     * this worker is no longer the holder and has nothing to say about when the tenant is next due.
     * That is the design, not a lost write, and it is why this returns void. It also clears the token
     * on the way out: a tenant sitting at its next due time is held by nobody, and leaving a spent
     * token behind would make the row read as leased for as long as it sat there.
     */
    public void release(String queue, UUID scope, UUID lease, Instant dueAt) {
        if (dueAt == null) {
            jdbc.update("delete from platform.queue_signal where queue = ? and org_id = ? and lease = ?",
                    queue, scope, lease);
            return;
        }
        jdbc.update("""
                update platform.queue_signal set due_at = greatest(?, now()), lease = null
                where queue = ? and org_id = ? and lease = ?
                """, Timestamp.from(dueAt), queue, scope, lease);
    }

    /**
     * A tenant one worker holds: {@link #scope} is whose work it is, {@link #lease} is the token that
     * proves this worker is still the holder when it comes back to {@link #release}.
     *
     * <p>The token carries no expiry of its own, and does not need to: the row's {@code due_at} already
     * says when the tenant becomes claimable again whether or not this worker ever returns, which is
     * the crash path and the reason a lease is a column rather than a lock. The token answers the other
     * question — WHO — and only that.
     */
    public record Leased(UUID scope, UUID lease) {
    }
}
