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
                insert into platform.event_inbox (listener_id, message_id, processed_at)
                values (?, ?, ?) on conflict do nothing
                """, listenerId, messageId, Timestamp.from(clock.instant()));
        return inserted == 1;
    }

    /**
     * One bounded batch of inbox rows older than the cutoff; the caller loops until a short batch.
     * Dedup only needs to cover the at-least-once redelivery window (a restart re-publishing
     * incomplete publications) — not all history, which is what this table accumulated before the
     * purge existed.
     *
     * <p>THE TRAP, and the reason the {@code order by} is not decoration: the sub-select is only
     * bounded work because V52 indexes {@code processed_at}. Unindexed, "the thousand oldest" can
     * only be found by seq-scanning and top-N sorting everything past the cutoff on EVERY batch, so
     * the caller's 100-batch loop costs 100 full scans and the batching is a pessimisation rather
     * than a fix. With the index the scan IS the order and {@code limit} stops it at 1000 rows; the
     * outer delete then drives the primary key from those, which was never the expensive half.
     */
    public int purgeProcessedBatch(java.time.Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                delete from platform.event_inbox where (listener_id, message_id) in (
                    select listener_id, message_id from platform.event_inbox
                    where processed_at < ?
                    order by processed_at
                    limit ?)
                """, Timestamp.from(cutoff), batchSize);
    }
}
