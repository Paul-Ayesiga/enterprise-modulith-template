package ug.co.smsone.shared.events;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.CrossDatabaseWrites;

/**
 * Idempotent-consumer helper (the "inbox" half of outbox/inbox — the outbox is the Modulith
 * DB-backed event publication registry). Event delivery is at-least-once: registry re-publishes
 * incomplete publications on restart. Listeners with side effects call
 * {@code recordIfNew(listenerId, messageId)} first and skip when it returns false. The message id
 * is derived from business identity (e.g. {@code "setting:" + key + ":" + version}) since domain
 * events carry no envelope id.
 *
 * <h2>{@code event_inbox} is platform-tier, and most listeners are not on the platform axis by luck</h2>
 *
 * <p>Most consumers are {@code @ApplicationModuleListener}s, which run through {@code AsyncConfig}'s
 * {@code TaskDecorator} and therefore on the PLATFORM axis — for those this table has always been
 * reached correctly. {@code WebhookEventListener} is the exception and it is deliberate: its tables are
 * tenant-tier, so it unbundles the annotation and pins the EVENT'S org before opening its transaction
 * (ADR 0010 §3.2). Which means {@code WebhookDispatcher}'s claim below has been running on that
 * tenant's connection, and on a tenant served from another database
 * {@code platform.event_inbox} is not there (ADR 0011 §5.1). {@link CrossDatabaseWrites} converts it:
 * a no-op for every co-located caller, a separate borrow from primary for a remote one.
 *
 * <p><strong>What the conversion costs, named at the seam rather than discovered in production.</strong>
 * For a co-located tenant the claim still commits inside the listener's transaction and the guarantee
 * is unchanged: claim and side effects are one write, so a rollback un-claims. For a REMOTE tenant it
 * cannot — there is no cross-database atomic write — so the claim commits on primary first and the
 * work follows on the tenant's database. A listener that then fails would leave the message claimed and
 * its side effects absent, and the redelivery would be de-duplicated away: a fan-out silently dropped,
 * which is the exact failure {@code WebhookEventListener}'s javadoc says the transaction exists to
 * prevent. {@link #forget} is the repair, and the affected listener calls it in its failure path.
 */
@Component
public class EventInbox {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final CrossDatabaseWrites platformTier;

    public EventInbox(JdbcTemplate jdbcTemplate, Clock clock, CrossDatabaseWrites platformTier) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.platformTier = platformTier;
    }

    /** True exactly once per (listener, message) — false means already processed, skip. */
    public boolean recordIfNew(String listenerId, String messageId) {
        int inserted = platformTier.callOnPlatform(() -> jdbcTemplate.update("""
                insert into platform.event_inbox (listener_id, message_id, processed_at)
                values (?, ?, ?) on conflict do nothing
                """, listenerId, messageId, Timestamp.from(clock.instant())));
        return inserted == 1;
    }

    /**
     * Gives a claim back so the next redelivery of the same message processes it — the {@code release}
     * half that {@link #recordIfNew}'s {@code claim} half implies, and that only became necessary when
     * the two could commit apart (class note).
     *
     * <p>Safe to call unconditionally in a failure path, which is why the one caller does. On a
     * co-located tenant the claim rolled back with the work that failed, so this deletes nothing; on a
     * remote one it deletes the row that would otherwise swallow the retry. A caller that had to ask
     * which case it was in would eventually ask wrong.
     *
     * <p>It is NOT a general "un-process this" — nothing else may call it. Deleting a claim whose side
     * effects DID land turns at-least-once into at-least-twice, and the reason {@code recordIfNew}
     * exists is that some of those side effects (a webhook POST, a notification) are not idempotent at
     * the far end.
     */
    public void forget(String listenerId, String messageId) {
        platformTier.runOnPlatform(() -> jdbcTemplate.update(
                "delete from platform.event_inbox where listener_id = ? and message_id = ?",
                listenerId, messageId));
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
        return platformTier.callOnPlatform(() -> jdbcTemplate.update("""
                delete from platform.event_inbox where (listener_id, message_id) in (
                    select listener_id, message_id from platform.event_inbox
                    where processed_at < ?
                    order by processed_at
                    limit ?)
                """, Timestamp.from(cutoff), batchSize));
    }
}
