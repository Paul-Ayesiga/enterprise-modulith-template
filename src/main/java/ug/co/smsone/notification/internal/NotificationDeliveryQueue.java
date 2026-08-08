package ug.co.smsone.notification.internal;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.persistence.DbDialect;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.shared.queue.QueueSignals;

/**
 * The delivery queue, backed by {@code notification_delivery} and driven with plain JDBC (a job
 * queue is a poor fit for JPA/optimistic-locking). Enqueue is a single batch insert; {@link #claim}
 * atomically grabs a batch with {@code FOR UPDATE SKIP LOCKED} so multiple workers/instances never
 * double-claim. Status updates are short, single-statement, never wrap network I/O, and are FENCED:
 * each requires the row to still be PROCESSING at the claimant's attempts count, so a slow worker
 * whose claim went stale and was re-claimed can no longer corrupt the new owner's state.
 *
 * <h2>Phase 3 gave this queue the signal for FAIRNESS ONLY — the table did not move</h2>
 *
 * <p>{@code notification_delivery} stayed platform-tier (ADR 0010 §2 row 27): highest rate of the three
 * queues, {@code org_id} genuinely nullable, pure transport that is drained rather than dumped when a
 * tenant is extracted. So unlike {@code webhook_delivery} this claim never needed a tenant PIN — every
 * statement here names {@code platform.} and always will. What it did need was the other half of ADR
 * 0010 §2.1: the accepted cost the ADR states in the same breath as the tier decision is that <em>one
 * tenant's backlog delays another's</em>, and the sentence after it says to mitigate that with
 * fairness rather than with per-tenant tables. {@code platform.queue_signal} is that mitigation. The
 * claim is now scoped to one tenant at a time, so a tenant sitting on ten thousand queued messages
 * takes one batch and goes to the back of the queue instead of owning every batch until it drains.
 */
@Component
class NotificationDeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryQueue.class);
    private static final int MAX_ERROR = 1000;

    /** This queue's discriminator in {@code platform.queue_signal}. */
    static final String QUEUE = "notification";

    private final JdbcTemplate jdbc;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final DbDialect dialect;
    private final QueueSignals signals;
    private final TransactionTemplate transactions;

    NotificationDeliveryQueue(JdbcTemplate jdbc, io.micrometer.core.instrument.MeterRegistry meters,
            DbDialect dialect, QueueSignals signals, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.dialect = dialect;
        this.signals = signals;
        this.transactions = transactions;
    }

    /**
     * One batch insert plus <strong>one signal row per distinct scope, not per delivery</strong> — a
     * 300-recipient fan-out announces itself once (ADR 0010 §2.1).
     *
     * <p>The transaction is load-bearing: rows and signal have to become visible together, or a worker
     * can claim the scope in the window between them, find nothing, delete the signal, and leave the
     * batch queued with nothing left to look at it. See {@code QueueSignals} for the full argument.
     *
     * <p>A null {@code orgId} is not a missing tenant here — it is a platform-scoped notification, and
     * V41 added the column late precisely because most of them are. Those rows key on
     * {@link QueueSignals#PLATFORM_SCOPE}, which is what lets one not-null column serve both.
     */
    void enqueue(List<NewDelivery> deliveries, int maxAttempts) {
        if (deliveries.isEmpty()) {
            return;
        }
        transactions.executeWithoutResult(tx -> {
            jdbc.batchUpdate("""
                    insert into platform.notification_delivery
                        (id, channel, recipient, subject, body, org_id, status, attempts, max_attempts, next_attempt_at, created_at)
                    values (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, now(), now())
                    """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    NewDelivery d = deliveries.get(i);
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, d.channel().name());
                    ps.setString(3, d.recipient());
                    ps.setString(4, truncate(d.subject() == null ? "" : d.subject(), 255)); // subject is NOT NULL
                    ps.setString(5, d.body());
                    ps.setObject(6, d.orgId());
                    ps.setInt(7, maxAttempts);
                }

                @Override
                public int getBatchSize() {
                    return deliveries.size();
                }
            });
            deliveries.stream().map(d -> QueueSignals.scopeOf(d.orgId())).distinct()
                    .forEach(scope -> signals.raise(QUEUE, scope));
        });
    }

    /**
     * Atomically claim up to {@code batchSize} of ONE scope's eligible rows (PENDING and due, or
     * PROCESSING whose lock has gone stale), mark them PROCESSING, bump attempts, and return them.
     * Concurrent workers skip each other's locked rows. A row whose stored channel no longer maps to
     * the enum is dead-lettered here instead of poisoning every batch it lands in.
     *
     * <p><b>The scope predicate is what the {@code limit} now applies to, and that is the whole
     * fairness change.</b> Before it, {@code order by next_attempt_at limit batchSize} handed the batch
     * to whoever held the oldest rows — so a single tenant with more than {@code batchSize} due
     * messages took every slot of every poll until it drained. The caller takes one scope from
     * {@code platform.queue_signal} first, so the batch is bounded to that scope by construction.
     *
     * <p>Two spellings of the predicate rather than {@code is not distinct from}, deliberately.
     * {@code org_id is null} and {@code org_id = ?} are both index-searchable against V53's
     * {@code idx_notification_delivery_org}; {@code is not distinct from} is not searchable at all and
     * would turn the hot claim into a filter over the whole queue. The shape of the rest of the
     * statement is untouched: the {@code OR} of the two liveness arms stays an {@code OR} (V50's
     * {@code idx_notification_delivery_claim} is built for it), and it is parenthesised so the new
     * {@code and} cannot bind to only the first arm — which would have made every stale reclaim
     * cross-tenant again and every one of them a double send.
     */
    List<ClaimedDelivery> claim(UUID scope, int batchSize, Duration staleLock) {
        List<Object> args = new ArrayList<>(scopeArgs(scope));
        args.add(staleLock.toMillis()); // millis, not seconds — a sub-second staleLock must not floor to 0
        args.add(batchSize);
        List<ClaimedDelivery> claimed = jdbc.query("""
                update platform.notification_delivery d
                set status = 'PROCESSING', locked_at = now(), attempts = attempts + 1
                from (
                    select id from platform.notification_delivery
                    where %1$s
                      and ((status = 'PENDING' and next_attempt_at <= now())
                        or (status = 'PROCESSING' and locked_at < now() - (? * interval '1 millisecond')))
                    order by next_attempt_at
                    limit ?
                    %2$s
                ) c
                where d.id = c.id
                returning d.id, d.channel, d.recipient, d.subject, d.body, d.org_id, d.attempts,
                          d.max_attempts, d.created_at, d.throttled_since
                """.formatted(scopeFilter(scope), dialect.skipLocked()),
                (rs, rowNum) -> {
                    Timestamp throttledSince = rs.getTimestamp("throttled_since");
                    return new ClaimedDelivery(
                            rs.getObject("id", UUID.class),
                            channelOrNull(rs.getString("channel")),
                            rs.getString("recipient"),
                            rs.getString("subject"),
                            rs.getString("body"),
                            rs.getObject("org_id", UUID.class),
                            rs.getInt("attempts"),
                            rs.getInt("max_attempts"),
                            rs.getTimestamp("created_at").toInstant(),
                            throttledSince == null ? null : throttledSince.toInstant());
                },
                args.toArray());
        List<ClaimedDelivery> deliverable = new ArrayList<>(claimed.size());
        for (ClaimedDelivery delivery : claimed) {
            if (delivery.channel() == null) {
                log.warn("Delivery {} has an unrecognized channel value — dead-lettering", delivery.id());
                deadLetter(delivery.id(), "Unrecognized channel value", delivery.attempts());
                // This give-up happens at claim time, before the worker's counter helper can see
                // it — counted here or a bad channel rename dead-letters a whole backlog invisibly.
                io.micrometer.core.instrument.Counter.builder("smsone.deliveries.dead_lettered")
                        .description("Deliveries given up on, by queue and reason")
                        .tag("queue", "notification")
                        .tag("channel", "unknown")
                        .tag("reason", "unknown_channel")
                        .register(meters)
                        .increment();
            } else {
                deliverable.add(delivery);
            }
        }
        return deliverable;
    }

    void markSent(UUID id, int expectedAttempts) {
        fenced("markSent", id, jdbc.update(
                "update platform.notification_delivery set status = 'SENT', locked_at = null, last_error = null, "
                        + "throttled_since = null where id = ? and status = 'PROCESSING' and attempts = ?",
                id, expectedAttempts));
    }

    void reschedule(UUID id, Instant nextAttemptAt, String error, int expectedAttempts) {
        fenced("reschedule", id, jdbc.update(
                "update platform.notification_delivery set status = 'PENDING', next_attempt_at = ?, locked_at = null, "
                        + "last_error = ?, throttled_since = null where id = ? and status = 'PROCESSING' and attempts = ?",
                Timestamp.from(nextAttemptAt), truncate(error, MAX_ERROR), id, expectedAttempts));
    }

    void rescheduleThrottled(UUID id, Instant nextAttemptAt, int expectedAttempts) {
        // Throttling is not a failed attempt — undo the claim's increment so a rate-limited delivery
        // is never dead-lettered for lack of trying; remember when the throttled stretch began.
        fenced("rescheduleThrottled", id, jdbc.update(
                "update platform.notification_delivery set status = 'PENDING', next_attempt_at = ?, locked_at = null, "
                        + "attempts = greatest(attempts - 1, 0), throttled_since = coalesce(throttled_since, now()) "
                        + "where id = ? and status = 'PROCESSING' and attempts = ?",
                Timestamp.from(nextAttemptAt), id, expectedAttempts));
    }

    void deadLetter(UUID id, String error, int expectedAttempts) {
        fenced("deadLetter", id, jdbc.update(
                "update platform.notification_delivery set status = 'FAILED', locked_at = null, last_error = ? "
                        + "where id = ? and status = 'PROCESSING' and attempts = ?",
                truncate(error, MAX_ERROR), id, expectedAttempts));
    }

    /**
     * One bounded batch of terminal rows (SENT and FAILED) older than the cutoff; the caller loops
     * until a short batch, each committing on its own connection. FAILED is included deliberately —
     * a dead-letter past retention is stale noise nobody is coming back for, and matching
     * {@code webhook_delivery}'s rule keeps the two queues one pattern (AGENTS §7).
     */
    int purgeTerminalBatch(Instant cutoff, int batchSize) {
        return jdbc.update("""
                delete from platform.notification_delivery where id in (
                    select id from platform.notification_delivery
                    where status in ('SENT', 'FAILED') and created_at < ?
                    order by created_at
                    limit ?)
                """, Timestamp.from(cutoff), batchSize);
    }

    /**
     * Hands a leased scope back to {@code platform.queue_signal} with the time its earliest remaining
     * row becomes claimable — or deletes the signal when nothing is left.
     *
     * <p><b>Both arms, and losing the second one loses messages.</b> PENDING rows contribute their
     * {@code next_attempt_at}; PROCESSING rows contribute {@code locked_at + staleLock}, which is when
     * the stale-lock reclaim can take them. Drop that arm and a message whose worker died mid-send
     * leaves a scope whose signal says "nothing due": the row stays PROCESSING, no reclaim ever looks
     * at it, and nothing anywhere raises an error. {@code least(…)} over two scalar subqueries because
     * either can be empty, and {@code least} ignores NULL.
     *
     * <p>The scope predicate is repeated rather than parameterised twice for one reason: the platform
     * scope spells it {@code org_id is null} and carries no parameter at all, so the two subqueries take
     * the same fragment and the argument list is built from the same helper that produced it.
     */
    void releaseSignal(UUID scope, UUID lease, Duration staleLock) {
        List<Object> args = new ArrayList<>(scopeArgs(scope));
        args.addAll(scopeArgs(scope));
        args.add(staleLock.toMillis());
        Timestamp due = jdbc.queryForObject("""
                select least(
                    (select min(next_attempt_at) from platform.notification_delivery
                      where %1$s and status = 'PENDING'),
                    (select min(locked_at) from platform.notification_delivery
                      where %1$s and status = 'PROCESSING') + (? * interval '1 millisecond'))
                """.formatted(scopeFilter(scope)), Timestamp.class, args.toArray());
        signals.release(QUEUE, scope, lease, due == null ? null : due.toInstant());
    }

    /**
     * The scope predicate for a {@code queue_signal} key, and its bound arguments — always produced
     * together, because the platform scope has a predicate with no parameter and every caller that
     * built one without the other would bind the wrong list.
     */
    private static String scopeFilter(UUID scope) {
        return QueueSignals.isPlatformScope(scope) ? "org_id is null" : "org_id = ?";
    }

    private static List<Object> scopeArgs(UUID scope) {
        return QueueSignals.isPlatformScope(scope) ? List.of() : List.of(scope);
    }

    private static void fenced(String operation, UUID id, int updated) {
        if (updated == 0) {
            // Our claim went stale and another worker re-claimed the row; its state is authoritative.
            log.warn("Stale {} for delivery {} ignored (row was re-claimed)", operation, id);
        }
    }

    private static NotificationChannel channelOrNull(String raw) {
        try {
            return NotificationChannel.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
