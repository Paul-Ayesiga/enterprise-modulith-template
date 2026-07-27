package ug.co.smsone.shared.events;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent-consumer helper (the "inbox" half of outbox/inbox — the outbox is the Modulith
 * DB-backed event publication registry). Event delivery is at-least-once: registry re-publishes
 * incomplete publications on restart. Listeners with side effects call
 * {@code recordIfNew(listenerId, messageId)} first and skip when it returns false. The message id
 * is derived from business identity (e.g. {@code "setting:" + key + ":" + version}) since domain
 * events carry no envelope id.
 */
@Component
public class EventInbox {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public EventInbox(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** True exactly once per (listener, message) — false means already processed, skip. */
    public boolean recordIfNew(String listenerId, String messageId) {
        int inserted = jdbcTemplate.update("""
                insert into event_inbox (listener_id, message_id, processed_at)
                values (?, ?, ?) on conflict do nothing
                """, listenerId, messageId, Timestamp.from(clock.instant()));
        return inserted == 1;
    }
}
