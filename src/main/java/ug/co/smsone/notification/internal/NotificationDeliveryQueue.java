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
import ug.co.smsone.notification.NotificationChannel;

/**
 * The delivery queue, backed by {@code notification_delivery} and driven with plain JDBC (a job
 * queue is a poor fit for JPA/optimistic-locking). Enqueue is a single batch insert; {@link #claim}
 * atomically grabs a batch with {@code FOR UPDATE SKIP LOCKED} so multiple workers/instances never
 * double-claim. Status updates are short, single-statement, never wrap network I/O, and are FENCED:
 * each requires the row to still be PROCESSING at the claimant's attempts count, so a slow worker
 * whose claim went stale and was re-claimed can no longer corrupt the new owner's state.
 */
@Component
class NotificationDeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryQueue.class);
    private static final int MAX_ERROR = 1000;

    private final JdbcTemplate jdbc;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final DbDialect dialect;

    NotificationDeliveryQueue(JdbcTemplate jdbc, io.micrometer.core.instrument.MeterRegistry meters,
            DbDialect dialect) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.dialect = dialect;
    }

    void enqueue(List<NewDelivery> deliveries, int maxAttempts) {
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
    }

    /**
     * Atomically claim up to {@code batchSize} eligible rows (PENDING and due, or PROCESSING whose
     * lock has gone stale), mark them PROCESSING, bump attempts, and return them. Concurrent workers
     * skip each other's locked rows. A row whose stored channel no longer maps to the enum is
     * dead-lettered here instead of poisoning every batch it lands in.
     */
    List<ClaimedDelivery> claim(int batchSize, Duration staleLock) {
        List<ClaimedDelivery> claimed = jdbc.query("""
                update platform.notification_delivery d
                set status = 'PROCESSING', locked_at = now(), attempts = attempts + 1
                from (
                    select id from platform.notification_delivery
                    where (status = 'PENDING' and next_attempt_at <= now())
                       or (status = 'PROCESSING' and locked_at < now() - (? * interval '1 millisecond'))
                    order by next_attempt_at
                    limit ?
                    %s
                ) c
                where d.id = c.id
                returning d.id, d.channel, d.recipient, d.subject, d.body, d.org_id, d.attempts,
                          d.max_attempts, d.created_at, d.throttled_since
                """.formatted(dialect.skipLocked()),
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
                staleLock.toMillis(), batchSize); // millis, not seconds — a sub-second staleLock must not floor to 0
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
