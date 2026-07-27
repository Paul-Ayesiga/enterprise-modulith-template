package ug.co.smsone.notification.internal;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannel;

/**
 * The delivery queue, backed by {@code notification_delivery} and driven with plain JDBC (a job
 * queue is a poor fit for JPA/optimistic-locking). Enqueue is a single batch insert; {@link #claim}
 * atomically grabs a batch with {@code FOR UPDATE SKIP LOCKED} so multiple workers/instances never
 * double-claim. Status updates are short, single-statement, and never wrap network I/O.
 */
@Component
class NotificationDeliveryQueue {

    private static final int MAX_ERROR = 1000;

    private final JdbcTemplate jdbc;

    NotificationDeliveryQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void enqueue(List<NewDelivery> deliveries, int maxAttempts) {
        jdbc.batchUpdate("""
                insert into notification_delivery
                    (id, channel, recipient, subject, body, status, attempts, max_attempts, next_attempt_at, created_at)
                values (?, ?, ?, ?, ?, 'PENDING', 0, ?, now(), now())
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                NewDelivery d = deliveries.get(i);
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, d.channel().name());
                ps.setString(3, d.recipient());
                ps.setString(4, truncate(d.subject() == null ? "" : d.subject(), 255)); // subject is NOT NULL
                ps.setString(5, d.body());
                ps.setInt(6, maxAttempts);
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
     * skip each other's locked rows.
     */
    List<ClaimedDelivery> claim(int batchSize, Duration staleLock) {
        return jdbc.query("""
                update notification_delivery d
                set status = 'PROCESSING', locked_at = now(), attempts = attempts + 1
                from (
                    select id from notification_delivery
                    where (status = 'PENDING' and next_attempt_at <= now())
                       or (status = 'PROCESSING' and locked_at < now() - (? * interval '1 millisecond'))
                    order by next_attempt_at
                    limit ?
                    for update skip locked
                ) c
                where d.id = c.id
                returning d.id, d.channel, d.recipient, d.subject, d.body, d.attempts, d.max_attempts
                """,
                (rs, rowNum) -> new ClaimedDelivery(
                        rs.getObject("id", UUID.class),
                        NotificationChannel.valueOf(rs.getString("channel")),
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts")),
                staleLock.toMillis(), batchSize); // millis, not seconds — a sub-second staleLock must not floor to 0
    }

    void markSent(UUID id) {
        jdbc.update("update notification_delivery set status = 'SENT', locked_at = null, last_error = null where id = ?", id);
    }

    void reschedule(UUID id, Instant nextAttemptAt, String error) {
        jdbc.update("update notification_delivery set status = 'PENDING', next_attempt_at = ?, locked_at = null, last_error = ? where id = ?",
                Timestamp.from(nextAttemptAt), truncate(error, MAX_ERROR), id);
    }

    void deadLetter(UUID id, String error) {
        jdbc.update("update notification_delivery set status = 'FAILED', locked_at = null, last_error = ? where id = ?",
                truncate(error, MAX_ERROR), id);
    }

    int purgeSentBefore(Instant cutoff) {
        return jdbc.update("delete from notification_delivery where status = 'SENT' and created_at < ?", Timestamp.from(cutoff));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
