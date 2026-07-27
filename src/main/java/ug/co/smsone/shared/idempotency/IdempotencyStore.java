package ug.co.smsone.shared.idempotency;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** JDBC-backed store for idempotency claims and completed responses, scoped per principal. */
@Component
public class IdempotencyStore {

    public record StoredResponse(String requestHash, Integer status, String body, String contentType) {

        public boolean inProgress() {
            return status == null;
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public IdempotencyStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Claims the key atomically. An in-progress claim older than {@code lease} is taken over —
     * a crashed instance must not wedge the key until the purge job runs.
     */
    public boolean claim(String principal, String key, String requestHash, Duration lease) {
        Instant now = clock.instant();
        int changed = jdbcTemplate.update("""
                insert into idempotency_key (principal, idem_key, request_hash, created_at)
                values (?, ?, ?, ?)
                on conflict (principal, idem_key) do update
                    set request_hash = excluded.request_hash, created_at = excluded.created_at
                    where idempotency_key.response_status is null
                      and idempotency_key.created_at < ?
                """, principal, key, requestHash, Timestamp.from(now), Timestamp.from(now.minus(lease)));
        return changed == 1;
    }

    public Optional<StoredResponse> find(String principal, String key) {
        return jdbcTemplate.query("""
                        select request_hash, response_status, response_body, content_type
                        from idempotency_key where principal = ? and idem_key = ?
                        """,
                        (rs, rowNum) -> new StoredResponse(
                                rs.getString("request_hash"),
                                rs.getObject("response_status", Integer.class),
                                rs.getString("response_body"),
                                rs.getString("content_type")),
                        principal, key)
                .stream().findFirst();
    }

    public void complete(String principal, String key, int status, String body, String contentType) {
        jdbcTemplate.update("""
                update idempotency_key
                set response_status = ?, response_body = ?, content_type = ?
                where principal = ? and idem_key = ?
                """, status, body, contentType, principal, key);
    }

    /** Frees the key after a failed execution so the client can retry. */
    public void release(String principal, String key) {
        jdbcTemplate.update("delete from idempotency_key where principal = ? and idem_key = ?",
                principal, key);
    }

    public int purgeOlderThan(Duration retention) {
        return jdbcTemplate.update("delete from idempotency_key where created_at < ?",
                Timestamp.from(clock.instant().minus(retention)));
    }
}
