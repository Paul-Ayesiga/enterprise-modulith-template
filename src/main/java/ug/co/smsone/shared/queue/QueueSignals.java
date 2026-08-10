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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ug.co.smsone.shared.persistence.DbDialect;
import ug.co.smsone.shared.tenancy.CrossDatabaseWrites;

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
 * <h2>ADR 0011: the same-transaction rule survives where it can and is traded where it cannot</h2>
 *
 * <p>This table is platform-tier and lives on PRIMARY only (ADR 0011 §5, and the reason is in the
 * paragraph above: a claim searches for work before a tenant is chosen, so the table that answers
 * "which tenant" cannot follow a tenant). For every tenant co-located with primary — which is every
 * tenant on every deployment with no remote datasource configured — nothing below changes: the signal
 * still commits inside the transaction that writes the rows, and the invariant in rule 1 is held
 * exactly as it always was.
 *
 * <p>For a tenant served from ANOTHER database that invariant is not weakened, it is
 * <strong>unachievable</strong>: the rows are in one database, the signal in another, and this project
 * has no XA and wants none. {@link #raise} therefore takes the only honest alternative — the signal
 * becomes a separate transaction on primary, deferred until <em>after</em> the rows commit — and the
 * residue that ordering can still leave is swept by {@link #announceIfUnsignalled}. Both halves are
 * written up in ADR 0011 §5, including what is lost. See {@link #raise} for the ordering argument and
 * why after-the-rows is not merely the other choice but the strictly better one.
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
 * finds nothing releases it, which deletes the row when the tenant has nothing left at all. It is only
 * ever consulted to decide where to look next, never to decide what is true — which is why a signal
 * that is too EARLY costs one empty probe and a signal that is MISSING costs the work. Before ADR 0011
 * nothing reconciled this table and nothing needed to, because the missing case could not happen;
 * {@link #announceIfUnsignalled} exists because across two databases it can.
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
    private final CrossDatabaseWrites platformTier;

    QueueSignals(JdbcTemplate jdbc, DbDialect dialect, CrossDatabaseWrites platformTier) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.platformTier = platformTier;
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
     *
     * <h2>The one place ADR 0011 costs this class an invariant, and exactly how much</h2>
     *
     * <p>The rule above — raise inside the rows' transaction — is held whenever it CAN be, which is
     * whenever the caller's axis is on the platform database ({@code CrossDatabaseWrites
     * .onPlatformDatabase()}). That is every enqueue on every deployment today, so this branch is the
     * shipped behaviour and the shipped guarantee, unchanged.
     *
     * <p>A caller on a REMOTE tenant's axis cannot have it. Its rows commit on that tenant's database
     * and this row commits on primary; two databases, two commits, no snapshot, and no XA. So the raise
     * is <strong>deferred to after the rows commit</strong> and issued in its own transaction on
     * primary. That ordering is not a coin flip between two equally bad choices:
     *
     * <ul>
     *   <li><em>Raise BEFORE the rows commit</em> reproduces the exact failure rule 1 names: a worker
     *       claims the tenant, probes, finds nothing because the rows are still uncommitted, and
     *       {@code release(dueAt = null)} DELETES the signal — then the rows commit, indexed by
     *       nothing. That race needs only a concurrent poll, which is the normal state of a running
     *       fleet.</li>
     *   <li><em>Raise AFTER the rows commit</em> makes that same race self-healing: the probe may
     *       delete a signal, but the raise that follows re-creates one over rows that are now visible.
     *       What is left is a strictly smaller hole — the process dying in the window between the two
     *       commits — and unlike the first it cannot be provoked by load.</li>
     * </ul>
     *
     * <p><strong>What is lost, stated plainly:</strong> for a remote tenant, queued work is invisible to
     * every poll between its own commit and the signal's — sub-millisecond in the normal case, and
     * until the next reconciliation if this process dies in between. {@link #announceIfUnsignalled} is
     * that reconciliation, and it is not optional: without it a single ill-timed crash leaves a
     * tenant's webhooks or exports queued forever with no error anywhere, which is precisely the
     * silent-loss shape rule 1 was written to forbid.
     *
     * <p>The deferral rides {@code afterCommit} rather than a queue of our own, so a rolled-back
     * enqueue raises nothing — there are no rows to announce — and so the raise cannot run while the
     * caller's transaction still holds its locks.
     */
    public void raise(String queue, UUID scope) {
        if (CrossDatabaseWrites.onPlatformDatabase()) {
            upsertSignal(queue, scope);
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Nothing to wait for: no transaction means the rows this announces are already committed
            // (or there are none and the caller is a test driving the signal directly).
            platformTier.runOnPlatform(() -> upsertSignal(queue, scope));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Outside the caller's transaction by contract, so the platform pin is legal here and
                // the borrow that follows is a fresh one from primary.
                platformTier.runOnPlatform(() -> upsertSignal(queue, scope));
            }
        });
    }

    private void upsertSignal(String queue, UUID scope) {
        jdbc.update("""
                insert into platform.queue_signal (queue, org_id, due_at, lease)
                values (?, ?, now(), null)
                on conflict (queue, org_id)
                do update set due_at = least(queue_signal.due_at, excluded.due_at), lease = null
                """, queue, scope);
    }

    /**
     * <strong>The reconciling half: announce a scope that HAS claimable work and NO signal.</strong>
     * The repair for the hole {@link #raise} opens for a remote tenant, and modelled on
     * {@code SoftDeletePurgeJob.sweepSearchResidue} — this codebase's precedent for "a projection with
     * no delete path leaves residue forever, so something has to go looking for it".
     *
     * <p><strong>{@code do nothing}, never {@code do update}, and the difference is a live worker's
     * lease.</strong> A scope that already has a signal is already accounted for — possibly leased by a
     * worker mid-batch — and pulling its {@code due_at} forward or clearing its lease would void that
     * worker's release and park the tenant behind a lease nobody holds, which is the starvation this
     * whole table exists to remove. Reconciliation may only ever create what is missing.
     *
     * <p>{@code greatest(dueAt, now())} for the same reason {@code release} clamps: a backlog reports a
     * time in the past, and writing it literally would sort a recovered tenant ahead of everyone who
     * has been waiting honestly.
     *
     * @param dueAt when this scope's earliest claimable row becomes claimable — the queue's own
     *     remaining-work expression, so a queue never needs a second definition of "due"
     * @return true when a signal was created, which means work HAD been orphaned and is now visible
     */
    public boolean announceIfUnsignalled(String queue, UUID scope, Instant dueAt) {
        return platformTier.callOnPlatform(() -> jdbc.update("""
                insert into platform.queue_signal (queue, org_id, due_at, lease)
                values (?, ?, greatest(?, now()), null)
                on conflict (queue, org_id) do nothing
                """, queue, scope, Timestamp.from(dueAt))) == 1;
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
        // Pinned rather than trusted: all three workers already claim under callAsPlatform, and this
        // costs nothing when they do. It is here so that the class is correct for its callers rather
        // than correct because of them — the release below is exactly where that stopped being true.
        List<Leased> leased = platformTier.callOnPlatform(() -> jdbc.query("""
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
                queue, lease.toMillis(), token));
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
     *
     * <p><strong>Explicitly on the platform axis, and this is one of the call sites ADR 0011 §5.1's
     * "known set" missed.</strong> {@code WebhookDeliveryWorker.release} pins the TENANT — necessarily,
     * because {@code WebhookDeliveryQueue.releaseSignal} first computes the remaining-work time from
     * {@code webhook_delivery}, which is tenant-tier and unqualified — and then hands that time here.
     * So this statement ran on the tenant's connection, and for a remote tenant that is a database with
     * no {@code queue_signal} in it: the release throws, the lease is never given back, and the tenant
     * disappears from the queue for a whole stale-lock window every single batch.
     */
    public void release(String queue, UUID scope, UUID lease, Instant dueAt) {
        platformTier.runOnPlatform(() -> {
            if (dueAt == null) {
                jdbc.update(
                        "delete from platform.queue_signal where queue = ? and org_id = ? and lease = ?",
                        queue, scope, lease);
                return;
            }
            jdbc.update("""
                    update platform.queue_signal set due_at = greatest(?, now()), lease = null
                    where queue = ? and org_id = ? and lease = ?
                    """, Timestamp.from(dueAt), queue, scope, lease);
        });
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
