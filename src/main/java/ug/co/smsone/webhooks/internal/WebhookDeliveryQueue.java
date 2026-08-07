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

/**
 * The webhook delivery queue, backed by {@code webhook_delivery} and driven with plain JDBC. Enqueue is
 * one batch insert; {@link #claim} atomically grabs a batch with {@code FOR UPDATE SKIP LOCKED} (so
 * instances never double-claim) and joins the subscription to return its URL + signing secret — the
 * secret is thus never copied into the delivery row. Status updates are short, single-statement and
 * FENCED (row must still be PROCESSING at the claimant's attempt count), so a stale claimant whose row
 * was re-claimed can't corrupt the new owner's state.
 */
@Component
class WebhookDeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryQueue.class);
    private static final int MAX_ERROR = 1000;

    private final JdbcTemplate jdbc;
    private final DbDialect dialect;

    WebhookDeliveryQueue(JdbcTemplate jdbc, DbDialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    void enqueue(List<NewWebhookDelivery> deliveries, int maxAttempts) {
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
     */
    List<ClaimedWebhookDelivery> claim(int batchSize, Duration staleLock) {
        return jdbc.query("""
                update webhook_delivery d
                set status = 'PROCESSING', locked_at = now(), attempts = attempts + 1
                from (
                    select id from webhook_delivery
                    where id in (
                        (select id from webhook_delivery p
                         where p.status = 'PENDING' and p.next_attempt_at <= now()
                           and exists (select 1 from webhook_subscription s
                                       where s.id = p.subscription_id
                                         and s.deleted_at is null and s.status = 'ACTIVE')
                         order by p.next_attempt_at
                         limit ?)
                        union all
                        (select id from webhook_delivery r
                         where r.status = 'PROCESSING'
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
                batchSize, staleLock.toMillis(), batchSize, batchSize);
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
     * @return 1 when re-queued; 0 when the row is not FAILED (or not this org's/subscription's)
     */
    int requeueFailed(UUID deliveryId, UUID subscriptionId, UUID orgId, Instant now) {
        return jdbc.update(
                "update webhook_delivery set status = 'PENDING', attempts = 0, next_attempt_at = ?, "
                        + "locked_at = null where id = ? and subscription_id = ? and org_id = ? "
                        + "and status = 'FAILED'",
                Timestamp.from(now), deliveryId, subscriptionId, orgId);
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
