package ug.co.smsone.exchange.internal;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.Cursors;
import ug.co.smsone.shared.web.PageMeta;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The job queue, plain JDBC like the delivery queues, with the same discipline: one-at-a-time
 * {@code FOR UPDATE SKIP LOCKED} claims, EVERY mutation fenced on the claim's {@code attempts}
 * generation, stale-lock reclaim. What is different is deliberate: {@link #progress} commits the
 * batch's error rows IN THE SAME transaction as the offset that covers them (a crash loses neither
 * or both), doubles as the heartbeat that keeps a long job from being reclaimed mid-run, and
 * returns {@code cancel_requested} so the worker learns about a cancellation at the next batch
 * boundary without an extra round trip.
 */
@Component
class ExchangeJobStore {

    /** One outcome type, three answers: kept the claim, lost it, or asked to stop. */
    enum Progress { OK, LOST_CLAIM, CANCEL_REQUESTED }

    private static final Logger log = LoggerFactory.getLogger(ExchangeJobStore.class);
    private static final int MAX_ERROR = 500;

    private static final RowMapper<ExchangeJob> JOB = (rs, n) -> new ExchangeJob(
            rs.getObject("id", UUID.class),
            rs.getObject("org_id", UUID.class),
            rs.getString("requester"),
            rs.getString("job_type"),
            rs.getString("handler"),
            rs.getInt("handler_version"),
            rs.getString("format"),
            rs.getString("status"),
            rs.getString("source_key"),
            rs.getString("result_key"),
            rs.getString("error_report_key"),
            rs.getLong("processed"),
            rs.getLong("failed"),
            rs.getLong("next_offset"),
            rs.getInt("attempts"),
            rs.getBoolean("cancel_requested"),
            rs.getString("last_error"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ExchangeJobStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    UUID submit(UUID orgId, String requester, String jobType, String handler, int handlerVersion,
            String format, String sourceKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into exchange_job (id, org_id, requester, job_type, handler, handler_version,
                                          format, status, source_key, created_at)
                values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, id, orgId, requester, jobType, handler, handlerVersion, format, sourceKey,
                Timestamp.from(clock.instant()));
        return id;
    }

    /**
     * One job per claim — jobs are heavyweight; fan-out happens across instances, not within one.
     * The lock guard applies to PENDING too: a claimed job keeps its status until the runner's
     * first fenced transition, and without the guard a concurrent poller would double-claim it in
     * that window (harmless thanks to the fence, but a burned attempt).
     */
    Optional<ExchangeJob> claimOne(Duration staleLock) {
        List<ExchangeJob> claimed = jdbc.query("""
                update exchange_job j
                set locked_at = now(), attempts = attempts + 1, updated_at = now()
                from (
                    select id from exchange_job
                    where status in ('PENDING', 'VALIDATING', 'PROCESSING')
                      and (locked_at is null or locked_at < now() - (? * interval '1 millisecond'))
                    order by created_at
                    limit 1
                    for update skip locked
                ) c
                where j.id = c.id
                returning j.*
                """, JOB, staleLock.toMillis());
        return claimed.stream().findFirst();
    }

    /**
     * Mid-batch heartbeat: re-stamps the lock WITHOUT touching progress, so a batch whose records
     * do slow remote work (the members handler's provisioning round-trips) cannot silently exceed
     * the stale-lock and get double-claimed. False = the claim was lost — stop working immediately.
     */
    boolean heartbeat(UUID id, int attempts) {
        return jdbc.update("""
                update exchange_job set locked_at = now(), updated_at = now()
                where id = ? and attempts = ? and status in ('VALIDATING', 'PROCESSING')
                """, id, attempts) == 1;
    }

    /** Fenced status hop (e.g. PENDING→VALIDATING→PROCESSING). False = the claim was lost. */
    boolean transition(UUID id, int attempts, String from, String to) {
        return jdbc.update("""
                update exchange_job set status = ?, locked_at = now(), updated_at = now()
                where id = ? and attempts = ? and status = ?
                """, to, id, attempts, from) == 1;
    }

    /**
     * One batch's outcome, atomically: counters + resume offset + this batch's error rows commit
     * together, and the write doubles as the heartbeat. Error inserts are {@code on conflict do
     * nothing} — a replayed batch rewrites nothing.
     */
    Progress progress(UUID id, int attempts, long processed, long failed, long nextOffset,
            List<RowError> errors) {
        return transactions.execute(tx -> {
            if (!errors.isEmpty()) {
                jdbc.batchUpdate("""
                        insert into exchange_job_error (job_id, row_num, error)
                        values (?, ?, ?) on conflict do nothing
                        """, errors, errors.size(), (PreparedStatement ps, RowError error) -> {
                    ps.setObject(1, id);
                    ps.setLong(2, error.rowNum());
                    ps.setString(3, truncate(error.error()));
                });
            }
            List<Boolean> cancel = jdbc.query("""
                    update exchange_job
                    set processed = ?, failed = ?, next_offset = ?, locked_at = now(), updated_at = now()
                    where id = ? and attempts = ? and status = 'PROCESSING'
                    returning cancel_requested
                    """, (rs, n) -> rs.getBoolean(1), processed, failed, nextOffset, id, attempts);
            if (cancel.isEmpty()) {
                tx.setRollbackOnly(); // lost the claim — this batch's errors are the new owner's to write
                return Progress.LOST_CLAIM;
            }
            return cancel.getFirst() ? Progress.CANCEL_REQUESTED : Progress.OK;
        });
    }

    boolean markTerminal(UUID id, int attempts, String status, String resultKey, String errorReportKey,
            String lastError) {
        return jdbc.update("""
                update exchange_job
                set status = ?, result_key = coalesce(?, result_key),
                    error_report_key = coalesce(?, error_report_key),
                    last_error = ?, locked_at = null, updated_at = now()
                where id = ? and attempts = ? and status in ('PENDING', 'VALIDATING', 'PROCESSING')
                """, status, resultKey, errorReportKey, truncate(lastError), id, attempts) == 1;
    }

    /**
     * Retryable failure: release the claim so a later poll reclaims and RESUMES from next_offset —
     * after {@code backoff}. The claim predicate reads {@code locked_at < now() - staleLock}, so
     * "claimable at now + backoff" is written as {@code locked_at = now() + backoff - staleLock};
     * one column serves as both the lock and the retry schedule.
     */
    void releaseForRetry(UUID id, int attempts, String lastError, Duration backoff, Duration staleLock) {
        int updated = jdbc.update("""
                update exchange_job
                set locked_at = now() + (? * interval '1 millisecond') - (? * interval '1 millisecond'),
                    last_error = ?, updated_at = now()
                where id = ? and attempts = ? and status in ('VALIDATING', 'PROCESSING')
                """, backoff.toMillis(), staleLock.toMillis(), truncate(lastError), id, attempts);
        if (updated == 0) {
            log.warn("Stale release for exchange job {} ignored (row was re-claimed)", id);
        }
    }

    /**
     * Retention: oldest-first bounded batches over the V24 partial terminal index, excluding orgs
     * that carry their own retention override (handled per-org). A null org_id is platform-scoped
     * and never overridden, so it is purged at the default cutoff.
     */
    int purgeTerminalBatch(java.time.Instant cutoff, Collection<UUID> excludeOrgs, int batchSize) {
        if (excludeOrgs.isEmpty()) {
            return jdbc.update("""
                    delete from exchange_job where id in (
                        select id from exchange_job
                        where status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')
                          and created_at < ?
                        order by created_at
                        limit ?)
                    """, Timestamp.from(cutoff), batchSize);
        }
        String inClause = excludeOrgs.stream().map(o -> "?").collect(Collectors.joining(", "));
        Object[] args = new Object[excludeOrgs.size() + 2];
        args[0] = Timestamp.from(cutoff);
        int i = 1;
        for (UUID orgId : excludeOrgs) {
            args[i++] = orgId;
        }
        args[i] = batchSize;
        return jdbc.update("""
                delete from exchange_job where id in (
                    select id from exchange_job
                    where status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')
                      and created_at < ?
                      and (org_id is null or org_id not in (%s))
                    order by created_at
                    limit ?)
                """.formatted(inClause), args);
    }

    /** One org's terminal jobs older than its own cutoff — the per-org retention-override pass. */
    int purgeTerminalBatchForOrg(java.time.Instant cutoff, UUID orgId, int batchSize) {
        return jdbc.update("""
                delete from exchange_job where id in (
                    select id from exchange_job
                    where status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')
                      and created_at < ? and org_id = ?
                    order by created_at
                    limit ?)
                """, Timestamp.from(cutoff), orgId, batchSize);
    }

    boolean requestCancel(UUID id, UUID orgId) {
        return jdbc.update("""
                update exchange_job set cancel_requested = true, updated_at = now()
                where id = ? and org_id = ?
                  and status in ('PENDING', 'VALIDATING', 'PROCESSING')
                """, id, orgId) == 1;
    }

    Optional<ExchangeJob> find(UUID id, UUID orgId) {
        return jdbc.query("select * from exchange_job where id = ? and org_id = ?", JOB, id, orgId)
                .stream().findFirst();
    }

    /**
     * The finalize step streams the COMPLETE, ordered report from here. Streaming is real, not
     * claimed: pgjdbc only honors fetchSize inside a transaction with a forward-only cursor, so
     * this runs in one — otherwise a 100k-error report would materialize entirely in heap first.
     */
    void forEachError(UUID id, ErrorRowConsumer consumer) {
        transactions.executeWithoutResult(tx -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "select row_num, error from exchange_job_error where job_id = ? order by row_num");
            ps.setFetchSize(500);
            ps.setObject(1, id);
            return ps;
        }, rs -> {
            consumer.accept(rs.getLong("row_num"), rs.getString("error"));
        }));
    }

    <T> WindowedResult<T> list(UUID orgId, CursorPageRequest page,
            java.util.function.Function<ExchangeJob, T> mapper) {
        Cursor cursor = decode(page);
        List<Object> params = new java.util.ArrayList<>(List.of(orgId));
        String keyset = "";
        if (cursor != null) {
            keyset = " and (created_at, id) < (?, ?)";
            params.add(Timestamp.from(cursor.createdAt()));
            params.add(cursor.id());
        }
        params.add(page.size() + 1);
        List<ExchangeJob> rows = jdbc.query(
                "select * from exchange_job where org_id = ?" + keyset
                        + " order by created_at desc, id desc limit ?",
                JOB, params.toArray());
        boolean hasMore = rows.size() > page.size();
        List<ExchangeJob> pageRows = hasMore ? rows.subList(0, page.size()) : rows;
        String next = null;
        if (hasMore) {
            ExchangeJob last = pageRows.getLast();
            Map<String, Object> keys = new LinkedHashMap<>();
            keys.put("createdAt", last.createdAt());
            keys.put("id", last.id());
            next = Cursors.encode(org.springframework.data.domain.ScrollPosition.forward(keys));
        }
        List<T> items = pageRows.stream().map(mapper).toList();
        return new WindowedResult<>(items, new PageMeta(page.size(), items.size(), hasMore, next));
    }

    record RowError(long rowNum, String error) {
    }

    @FunctionalInterface
    interface ErrorRowConsumer {
        void accept(long rowNum, String error);
    }

    private record Cursor(java.time.Instant createdAt, UUID id) {
    }

    private static Cursor decode(CursorPageRequest page) {
        KeysetScrollPosition position = page.scrollPosition();
        if (position.getKeys().isEmpty()) {
            return null;
        }
        Map<String, Object> keys = position.getKeys();
        if (!(keys.get("createdAt") instanceof java.time.Instant createdAt)
                || !(keys.get("id") instanceof UUID id) || keys.size() != 2) {
            throw new ValidationException("page[after] is not a valid cursor for this collection.",
                    ApiSource.parameter("page[after]"));
        }
        return new Cursor(createdAt, id);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR ? value : value.substring(0, MAX_ERROR);
    }
}
