package ug.co.smsone.shared.idempotency;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.CrossDatabaseWrites;

/**
 * JDBC-backed store for idempotency claims and completed responses, scoped per principal.
 *
 * <h2>Every statement here is on the PLATFORM axis, explicitly, and that is not decoration</h2>
 *
 * <p>{@code idempotency_key} is platform-tier and lives on primary only (ADR 0011 §5): the key is
 * per-principal, a person is not a tenant, and one human retrying the same request against two of
 * their organizations must land on one key. But the CALLER is a request thread, and by the time
 * {@code IdempotencyFilter} ({@code @Order(1)}) runs, {@code CurrentUserFilter} ({@code @Order(-1)})
 * has already pinned that request's organization. So every statement below was being issued on the
 * TENANT's connection — invisible while every tenant is on primary, because the schema is right there
 * and named explicitly, and a hard {@code relation "platform.idempotency_key" does not exist} the
 * first time a tenant is served from another database. The whole endpoint 500s: not the write, the
 * <em>claim</em>, so an idempotent POST to a remote tenant never reaches its controller at all.
 *
 * <p>{@link CrossDatabaseWrites} is the conversion. It is a no-op — same connection, same transaction —
 * for a caller whose axis is already co-located with primary, so a co-located tenant's request costs
 * exactly what it costs today; a remote tenant's claim takes its own borrow from the primary pool.
 * There is no transactional coupling to lose here, which is what makes this the simplest of the
 * conversions: the filter runs before the controller's transaction opens and completes after it has
 * committed, so the claim was never in the same transaction as the work it fences.
 */
@Component
public class IdempotencyStore {

    public record StoredResponse(String requestHash, Integer status, String body, String contentType) {

        public boolean inProgress() {
            return status == null;
        }
    }

    // Same numbers as EventInboxPurgeJob's loop, for the same two reasons: a batch small enough that
    // its locks are never held long, and a run short enough to finish inside a PT30M ShedLock lease.
    // Package-private so the purge test pins the real LIMIT instead of hard-coding a copy of it.
    static final int PURGE_BATCH_SIZE = 1000;
    private static final int MAX_PURGE_BATCHES = 100;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final CrossDatabaseWrites platformTier;

    public IdempotencyStore(JdbcTemplate jdbcTemplate, Clock clock, CrossDatabaseWrites platformTier) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.platformTier = platformTier;
    }

    /**
     * Claims the key atomically, returning the claim's timestamp — the fence token for
     * {@link #complete} and {@link #release}. An in-progress claim older than {@code lease} is taken
     * over (a crashed instance must not wedge the key until the purge job runs); the returned
     * timestamp is what stops that taken-over claimant, should it wake up later, from destroying the
     * new owner's row — AGENTS §7's fencing rule, applied to this store itself.
     */
    public Optional<Instant> claim(String principal, String key, String requestHash, Duration lease) {
        Instant now = clock.instant();
        // PORTING: Postgres UPSERT with a conditional DO UPDATE. On another RDBMS this is a whole-
        // statement rewrite (Oracle/SQL Server MERGE, MySQL ON DUPLICATE KEY) — see docs/PORTING.md.
        int changed = platformTier.callOnPlatform(() -> jdbcTemplate.update("""
                insert into platform.idempotency_key (principal, idem_key, request_hash, created_at)
                values (?, ?, ?, ?)
                on conflict (principal, idem_key) do update
                    set request_hash = excluded.request_hash, created_at = excluded.created_at
                    where idempotency_key.response_status is null
                      and idempotency_key.created_at < ?
                """, principal, key, requestHash, Timestamp.from(now), Timestamp.from(now.minus(lease))));
        return changed == 1 ? Optional.of(now) : Optional.empty();
    }

    public Optional<StoredResponse> find(String principal, String key) {
        return platformTier.callOnPlatform(() -> jdbcTemplate.query("""
                        select request_hash, response_status, response_body, content_type
                        from platform.idempotency_key where principal = ? and idem_key = ?
                        """,
                        (rs, rowNum) -> new StoredResponse(
                                rs.getString("request_hash"),
                                rs.getObject("response_status", Integer.class),
                                rs.getString("response_body"),
                                rs.getString("content_type")),
                        principal, key))
                .stream().findFirst();
    }

    /** Fenced on {@code claimedAt}: after a lease takeover this claimant's write matches zero rows. */
    public void complete(String principal, String key, Instant claimedAt, int status, String body,
            String contentType) {
        platformTier.runOnPlatform(() -> jdbcTemplate.update("""
                update platform.idempotency_key
                set response_status = ?, response_body = ?, content_type = ?
                where principal = ? and idem_key = ? and created_at = ?
                """, status, body, contentType, principal, key, Timestamp.from(claimedAt)));
    }

    /**
     * Frees the key after a failed execution so the client can retry. Fenced on {@code claimedAt} —
     * an unfenced delete here would let a zombie claimant erase the takeover's in-progress row, and
     * a third duplicate would then re-claim and re-execute the side effect.
     */
    public void release(String principal, String key, Instant claimedAt) {
        platformTier.runOnPlatform(() -> jdbcTemplate.update(
                "delete from platform.idempotency_key where principal = ? and idem_key = ? and created_at = ?",
                principal, key, Timestamp.from(claimedAt)));
    }

    /**
     * Sweeps every key past retention in bounded batches, oldest first, and returns the total.
     *
     * <p>This used to be one unbatched {@code delete … where created_at < ?} across the whole
     * retention window: a single transaction taking a row lock per key and holding every one of them
     * until it commits. On a busy day that is most of the table, and {@link #claim} blocks on each
     * locked key for the duration — a nightly sweep turning into a request-path outage. Batching is
     * the fix {@code EventInbox.purgeProcessedBatch} already applies, so this follows it rather than
     * inventing a second shape: one statement per batch, each committing on its own (the purge job
     * is deliberately not {@code @Transactional}, AGENTS §7), so locks are released 1000 at a time.
     *
     * <p>THE TRAP: the sub-select is only cheap because V52 indexes {@code created_at}. Without that
     * index Postgres seq-scans and top-N sorts the whole table for EVERY batch, so 100 batches cost
     * 100 full scans — strictly worse than the single delete this replaced. Measured on 100k rows,
     * one batch: 28.9ms unindexed against 2.4ms indexed. The outer delete is fine either way; it
     * drives the primary key from the rows the sub-select returns.
     *
     * <p>{@value #MAX_PURGE_BATCHES} batches cap one run for the same reason the inbox purge caps
     * its own: the run must finish inside the caller's ShedLock lease. A key left behind is still
     * past retention tomorrow, so the remainder goes in the next nightly run.
     */
    public int purgeOlderThan(Duration retention) {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(retention));
        int total = 0;
        for (int batch = 0; batch < MAX_PURGE_BATCHES; batch++) {
            int deleted = purgeBatch(cutoff);
            total += deleted;
            if (deleted < PURGE_BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /**
     * One batch. Package-private so a test can drive a single one and see the bound hold.
     *
     * <p>Pinned like everything else here even though {@code IdempotencyPurgeJob} already wraps the
     * whole run in {@code callAsPlatform}: the pin costs nothing when the axis is already primary, and
     * a store whose correctness depends on remembering which of its six methods the caller pinned is a
     * store that will be called wrong.
     */
    int purgeBatch(Timestamp cutoff) {
        return platformTier.callOnPlatform(() -> jdbcTemplate.update("""
                delete from platform.idempotency_key where (principal, idem_key) in (
                    select principal, idem_key from platform.idempotency_key
                    where created_at < ?
                    order by created_at
                    limit ?)
                """, cutoff, PURGE_BATCH_SIZE));
    }
}
