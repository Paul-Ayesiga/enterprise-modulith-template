package ug.co.smsone.webhooks.internal;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
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

    WebhookDeliveryQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    List<ClaimedWebhookDelivery> claim(int batchSize, Duration staleLock) {
        return jdbc.query("""
                update webhook_delivery d
                set status = 'PROCESSING', locked_at = now(), attempts = attempts + 1
                from (
                    select id from webhook_delivery
                    where (status = 'PENDING' and next_attempt_at <= now())
                       or (status = 'PROCESSING' and locked_at < now() - (? * interval '1 millisecond'))
                    order by next_attempt_at
                    limit ?
                    for update skip locked
                ) c, webhook_subscription s
                where d.id = c.id and s.id = d.subscription_id
                returning d.id, s.url, s.secret, d.event_type, d.payload, d.attempts, d.max_attempts
                """,
                (rs, rowNum) -> new ClaimedWebhookDelivery(
                        rs.getObject("id", UUID.class),
                        rs.getString("url"),
                        rs.getString("secret"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts")),
                staleLock.toMillis(), batchSize);
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

    int purgeDeliveredBefore(Instant cutoff) {
        return jdbc.update("delete from webhook_delivery where status = 'DELIVERED' and created_at < ?",
                Timestamp.from(cutoff));
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
