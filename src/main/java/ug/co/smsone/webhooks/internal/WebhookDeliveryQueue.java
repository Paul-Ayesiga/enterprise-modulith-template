package ug.co.smsone.webhooks.internal;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.persistence.DbDialect;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.queue.QueueSignals;

/**
 * The webhook delivery queue, backed by {@code webhook_delivery} and driven with plain JDBC. Enqueue is
 * one batch insert; {@link #claim} atomically grabs a batch with {@code FOR UPDATE SKIP LOCKED} (so
 * instances never double-claim) and joins the subscription to return its URL + signing secret — the
 * secret is thus never copied into the delivery row. Status updates are short, single-statement and
 * FENCED (row must still be PROCESSING at the claimant's attempt count), so a stale claimant whose row
 * was re-claimed can't corrupt the new owner's state.
 *
 * <h2>Since Phase 3 the claim is in two steps, and this class owns the second one</h2>
 *
 * <p>{@code platform.queue_signal} answers "which tenant has work"; {@link WebhookDeliveryWorker} pins
 * that tenant; {@link #claim} then runs against ONE tenant's rows. That is what makes the join to
 * {@code webhook_subscription} — a tenant-tier table with no schema anyone can name once a tenant is
 * promoted — expressible at all, and it is what makes fairness a property of the shape rather than of
 * a predicate. Everything this class writes therefore keeps the signal honest: {@link #enqueue} and
 * {@link #requeueFailed} raise it in the same transaction as the rows, and {@link #releaseSignal}
 * recomputes it from what the tenant has left.
 */
@Component
class WebhookDeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryQueue.class);
    private static final int MAX_ERROR = 1000;

    /** This queue's discriminator in {@code platform.queue_signal}. */
    static final String QUEUE = "webhook";

    private final JdbcTemplate jdbc;
    private final DbDialect dialect;
    private final QueueSignals signals;
    private final TransactionTemplate transactions;

    WebhookDeliveryQueue(JdbcTemplate jdbc, DbDialect dialect, QueueSignals signals,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.signals = signals;
        this.transactions = transactions;
    }

    /**
     * One batch insert plus <strong>one signal row per distinct org, not per delivery</strong> — a
     * fan-out of forty thousand rows announces itself once (ADR 0010 §2.1).
     *
     * <p><strong>The transaction is the point, not tidiness.</strong> The rows and the signal that
     * indexes them have to become visible together: commit the rows first and a worker can claim the
     * tenant, find nothing, and delete the signal in the window before they land; commit the signal
     * first and the same probe deletes it before the rows exist. Either way the batch is queued, no
     * error is raised anywhere, and no poll ever looks at it again. Inside one transaction a concurrent
     * claim either sees both or skips the tenant entirely, because the upsert holds the signal row's
     * lock and {@code SKIP LOCKED} steps over it.
     *
     * <p>{@code distinct} rather than one raise per delivery: the dispatcher fans one event out inside a
     * single org, so this is one row in practice — but the signature takes a list of deliveries that
     * each carry their own {@code org_id}, and a mixed batch that announced only the first org would
     * strand the rest.
     */
    void enqueue(List<NewWebhookDelivery> deliveries, int maxAttempts) {
        if (deliveries.isEmpty()) {
            return;
        }
        transactions.executeWithoutResult(tx -> {
            jdbc.batchUpdate("""
                    insert into webhook_delivery
                        (id, subscription_id, org_id, event_type, payload, status, attempts, max_attempts,
                         next_attempt_at, created_at)
                    values (?, ?, ?, ?, ?, 'PENDING', 0, ?, now(), now())
                    """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    NewWebhookDelivery d = deliveries.get(i);
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, d.subscriptionId());
                    ps.setObject(3, d.orgId());
                    ps.setString(4, d.eventType());
                    ps.setString(5, d.payload());
                    ps.setInt(6, maxAttempts);
                }

                @Override
                public int getBatchSize() {
                    return deliveries.size();
                }
            });
            deliveries.stream().map(NewWebhookDelivery::orgId).distinct()
                    .forEach(orgId -> signals.raise(QUEUE, QueueSignals.scopeOf(orgId)));
        });
    }

    /**
     * {@code s.deleted_at is null} is the revocation, not a tidiness filter. Soft delete leaves the
     * subscription row in place, so the {@code on delete cascade} that used to wipe the queue never
     * fires; without this predicate a deleted subscription's queued deliveries keep being claimed and
     * keep being POSTed to the endpoint the tenant just revoked, signed with the secret they rotated
     * away from. {@code @SQLRestriction} cannot reach native SQL, so it is spelled out here.
     *
     * <p>{@code s.status = 'ACTIVE'} is the softer half of the same rule: DISABLED pauses delivery
     * immediately, queued rows included — not just future fan-out. Unlike a delete (which cancels
     * outstanding rows), paused rows stay PENDING and resume the moment the tenant re-enables.
     *
     * <p><b>Both predicates must be applied BEFORE the LIMIT, and that is the whole point of the shape
     * below.</b> They used to sit only in the outer join, after an inner {@code order by … limit ?} that
     * knew nothing about them — so a paused subscription's rows occupied claim slots they could never be
     * claimed from. Once the cumulative count of such rows reached {@code batchSize}, every poll claimed
     * ZERO and no webhook was delivered for ANY tenant, permanently: the stuck rows are never claimed, so
     * never rescheduled, so {@code attempts} never increments, so {@code max_attempts} never dead-letters
     * them, and {@code purgeTerminalBatch} only deletes DELIVERED/FAILED. Reproduced against seeded data
     * — 60 due rows moved onto one subscription and paused, 39,937 healthy deliveries queued behind them,
     * three consecutive claims returning 0. With the predicate inside, the same state claims a full 50.
     * The asymmetry that hid it: {@code delete()} calls {@code cancelOutstanding}, {@code update()} to
     * DISABLED does not.
     *
     * <p>The outer join keeps its copy of both predicates. That is not redundancy: it re-checks at UPDATE
     * time what the subquery saw at SELECT time, closing the window in which a tenant revokes between the
     * two.
     *
     * <p>The two arms are separated rather than left as one {@code OR} because an OR across the two
     * partial indexes forces a BitmapOr, and a bitmap scan returns no useful order — so
     * {@code order by next_attempt_at} had to sort the ENTIRE due backlog to pick 50, making claim cost
     * O(backlog) exactly when a backlog exists. Each arm now walks its own index in order and stops at
     * its own LIMIT; only the (at most 2 × batchSize) survivors are sorted. Measured on 40k due rows:
     * 16.7 ms / 3412 kB quicksort → 2.7 ms / 29 kB.
     *
     * <p>The locking clause sits on the OUTER select and not inside the arms because PostgreSQL rejects
     * {@code FOR UPDATE} anywhere in a UNION ("FOR UPDATE is not allowed with UNION/INTERSECT/EXCEPT"),
     * parenthesised branches included. The arms therefore choose candidates and the outer select — a
     * plain single-table read — takes the locks.
     *
     * <p><b>Since Phase 3 this claim covers exactly ONE tenant, and that is the fairness guarantee.</b>
     * {@link WebhookDeliveryWorker#drainOnce} takes a tenant from {@code platform.queue_signal} first,
     * pins it, and passes its {@code orgId} here; both arms carry {@code org_id = ?} INSIDE their own
     * limit, so the batch this statement returns can only ever be one tenant's. The outage the paragraph
     * above narrates — one subscription's rows occupying every slot until no tenant got a webhook — is
     * therefore impossible by construction now rather than prevented by a predicate: the slots are not
     * shared. The liveness predicates stay exactly where they are, because they still decide the
     * ordering WITHIN a tenant and because a tenant whose every row is paused must still yield an empty
     * batch rather than a batch of rows the outer join would discard.
     *
     * <p>The predicate goes in the two ARMS and deliberately not in the outer update: the arms are what
     * choose the ids and therefore what the {@code limit} applies to, and the outer statement already
     * joins on those ids. Adding a third copy would read as a safety check it is not — every id reaching
     * it came from an arm that already proved the org.
     *
     * <p>Both tables are tenant-tier (ADR 0010 §2), so the pin also decides the SCHEMA these names
     * resolve in — {@code tenant_pool} while the org is pooled, its own silo after promotion. That is
     * the property the two-step claim exists to buy: a single-step claim cannot pin what it has not
     * found yet, so it could never have named the schema this join needs.
     *
     * <p>The index this shape now wants is {@code (org_id, next_attempt_at) where status = 'PENDING'};
     * V15's {@code idx_webhook_del_due} orders correctly and then filters on the org, so a pooled schema
     * carrying several tenants' backlogs reads rows it discards. That DDL is tenant-tier and belongs in
     * {@code db/migration/tenant/} — V56's header records the reason it is not in the platform sequence.
     */
    List<ClaimedWebhookDelivery> claim(UUID orgId, int batchSize, Duration staleLock) {
        return jdbc.query("""
                update webhook_delivery d
                set status = 'PROCESSING', locked_at = now(), attempts = attempts + 1
                from (
                    select id from webhook_delivery
                    where id in (
                        (select id from webhook_delivery p
                         where p.org_id = ?
                           and p.status = 'PENDING' and p.next_attempt_at <= now()
                           and exists (select 1 from webhook_subscription s
                                       where s.id = p.subscription_id
                                         and s.deleted_at is null and s.status = 'ACTIVE')
                         order by p.next_attempt_at
                         limit ?)
                        union all
                        (select id from webhook_delivery r
                         where r.org_id = ?
                           and r.status = 'PROCESSING'
                           and r.locked_at < now() - (? * interval '1 millisecond')
                           and exists (select 1 from webhook_subscription s
                                       where s.id = r.subscription_id
                                         and s.deleted_at is null and s.status = 'ACTIVE')
                         order by r.locked_at
                         limit ?)
                    )
                    order by next_attempt_at
                    limit ?
                    %s
                ) c, webhook_subscription s
                where d.id = c.id and s.id = d.subscription_id and s.deleted_at is null
                  and s.status = 'ACTIVE'
                returning d.id, s.url, s.secret, d.event_type, d.payload, d.attempts, d.max_attempts
                """.formatted(dialect.skipLocked()),
                (rs, rowNum) -> new ClaimedWebhookDelivery(
                        rs.getObject("id", UUID.class),
                        rs.getString("url"),
                        rs.getString("secret"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts")),
                orgId, batchSize, orgId, staleLock.toMillis(), batchSize, batchSize);
    }

    /**
     * Hands a leased tenant back to {@code platform.queue_signal} with the time its earliest remaining
     * row becomes claimable — or deletes the signal when nothing is left. Runs on the tenant's own axis,
     * like everything else that names {@code webhook_delivery}.
     *
     * <p><b>Both arms, and losing either one is a lost delivery.</b> PENDING rows contribute their
     * {@code next_attempt_at}; PROCESSING rows contribute {@code locked_at + staleLock}, which is when
     * the stale-lock reclaim can take them. Drop the second and a delivery whose worker crashed
     * mid-POST leaves a tenant whose signal says "nothing due" — the row stays PROCESSING forever, no
     * reclaim ever looks at it, and the only symptom is one webhook that never arrived.
     * {@code least(…)} over two scalar subqueries rather than one query with an {@code OR}: each arm
     * walks its own partial index, and either arm being empty yields NULL, which {@code least} ignores.
     *
     * <p><b>The subscription-liveness predicates are deliberately NOT repeated here.</b> Applying them
     * would delete the signal of a tenant whose only queued rows sit behind a DISABLED subscription —
     * and re-enabling that subscription is a write to {@code webhook_subscription}, a table this queue
     * never watches, so nothing would raise the signal again and the backlog the pause was supposed to
     * hold would never resume. Counting those rows instead costs one empty probe per poll for as long
     * as the pause lasts, and the {@code greatest(…, now())} clamp in {@code QueueSignals.release}
     * keeps that tenant sorted behind everyone with real work. One empty query against a lost backlog
     * is not a close call.
     */
    void releaseSignal(UUID orgId, UUID lease, Duration staleLock) {
        Timestamp due = jdbc.queryForObject("""
                select least(
                    (select min(next_attempt_at) from webhook_delivery
                      where org_id = ? and status = 'PENDING'),
                    (select min(locked_at) + (? * interval '1 millisecond') from webhook_delivery
                      where org_id = ? and status = 'PROCESSING'))
                """, Timestamp.class, orgId, staleLock.toMillis(), orgId);
        signals.release(QUEUE, orgId, lease, due == null ? null : due.toInstant());
    }

    void markDelivered(UUID id, int expectedAttempts, int responseStatus) {
        fenced("markDelivered", id, jdbc.update(
                "update webhook_delivery set status = 'DELIVERED', locked_at = null, delivered_at = now(), "
                        + "response_status = ?, last_error = null where id = ? and status = 'PROCESSING' and attempts = ?",
                responseStatus, id, expectedAttempts));
    }

    void reschedule(UUID id, Instant nextAttemptAt, Integer responseStatus, String error, int expectedAttempts) {
        fenced("reschedule", id, jdbc.update(
                "update webhook_delivery set status = 'PENDING', next_attempt_at = ?, locked_at = null, "
                        + "response_status = ?, last_error = ? where id = ? and status = 'PROCESSING' and attempts = ?",
                Timestamp.from(nextAttemptAt), responseStatus, truncate(error), id, expectedAttempts));
    }

    void deadLetter(UUID id, Integer responseStatus, String error, int expectedAttempts) {
        fenced("deadLetter", id, jdbc.update(
                "update webhook_delivery set status = 'FAILED', locked_at = null, response_status = ?, "
                        + "last_error = ? where id = ? and status = 'PROCESSING' and attempts = ?",
                responseStatus, truncate(error), id, expectedAttempts));
    }

    /**
     * Re-queue a dead-lettered delivery for a fresh retry cycle (operator/agent-initiated, after
     * fixing the receiver). Fenced on {@code FAILED}: the worker never touches FAILED rows, so the
     * only race is two redeliveries — one wins, the other reads 0 rows and reports the conflict.
     * Attempts reset so backoff restarts; {@code last_error} is kept until the next attempt writes
     * its own outcome (an operator reading the log mid-retry still sees why it dead-lettered).
     *
     * <p>Raises the signal in the same transaction as the status flip, and only when the flip landed.
     * A dead-lettered row is invisible to every remaining-work computation, so this is the one path
     * that turns a tenant with nothing queued back into one with something queued — miss it and the
     * operator's redelivery sits PENDING with no signal, which looks exactly like "still trying".
     *
     * @return 1 when re-queued; 0 when the row is not FAILED (or not this org's/subscription's)
     */
    int requeueFailed(UUID deliveryId, UUID subscriptionId, UUID orgId, Instant now) {
        return transactions.execute(tx -> {
            int requeued = jdbc.update(
                    "update webhook_delivery set status = 'PENDING', attempts = 0, next_attempt_at = ?, "
                            + "locked_at = null where id = ? and subscription_id = ? and org_id = ? "
                            + "and status = 'FAILED'",
                    Timestamp.from(now), deliveryId, subscriptionId, orgId);
            if (requeued > 0) {
                signals.raise(QUEUE, QueueSignals.scopeOf(orgId));
            }
            return requeued;
        });
    }

    /**
     * Dead-letters everything still outstanding for a subscription. The claim above simply stops
     * seeing those rows, which would leave them PENDING until the retention purge — indistinguishable
     * from "still trying". Cancelling them makes the delivery log say what actually happened, and the
     * inner select re-selecting unclaimable ids forever is avoided.
     */
    int cancelOutstanding(UUID subscriptionId, String reason) {
        return jdbc.update("update webhook_delivery set status = 'FAILED', locked_at = null, "
                + "last_error = ? where subscription_id = ? and status in ('PENDING', 'PROCESSING')",
                truncate(reason), subscriptionId);
    }

    /**
     * One bounded batch of terminal rows (DELIVERED and FAILED) older than the cutoff; the caller
     * loops until a short batch, each batch committing on its own connection. FAILED is included
     * deliberately: a dead-letter past the retention window is stale noise nobody is coming back
     * for — the delivery log is a log, not an archive.
     */
    int purgeTerminalBatch(Instant cutoff, Collection<UUID> excludeOrgs, int batchSize) {
        if (excludeOrgs.isEmpty()) {
            return jdbc.update("""
                    delete from webhook_delivery where id in (
                        select id from webhook_delivery
                        where status in ('DELIVERED', 'FAILED') and created_at < ?
                        order by created_at
                        limit ?)
                    """, Timestamp.from(cutoff), batchSize);
        }
        // Orgs with a retention override are handled in their own pass — exclude them here.
        String inClause = excludeOrgs.stream().map(o -> "?").collect(Collectors.joining(", "));
        Object[] args = new Object[excludeOrgs.size() + 2];
        args[0] = Timestamp.from(cutoff);
        int i = 1;
        for (UUID orgId : excludeOrgs) {
            args[i++] = orgId;
        }
        args[i] = batchSize;
        return jdbc.update("""
                delete from webhook_delivery where id in (
                    select id from webhook_delivery
                    where status in ('DELIVERED', 'FAILED') and created_at < ?
                      and org_id not in (%s)
                    order by created_at
                    limit ?)
                """.formatted(inClause), args);
    }

    /** One org's terminal deliveries older than its own cutoff — the per-org retention-override pass. */
    int purgeTerminalBatchForOrg(Instant cutoff, UUID orgId, int batchSize) {
        return jdbc.update("""
                delete from webhook_delivery where id in (
                    select id from webhook_delivery
                    where status in ('DELIVERED', 'FAILED') and created_at < ? and org_id = ?
                    order by created_at
                    limit ?)
                """, Timestamp.from(cutoff), orgId, batchSize);
    }

    private static void fenced(String operation, UUID id, int updated) {
        if (updated == 0) {
            log.warn("Stale {} for webhook delivery {} ignored (row was re-claimed)", operation, id);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR ? value : value.substring(0, MAX_ERROR);
    }
}
