package ug.co.smsone.shared.idempotency;

import java.time.Clock;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** JDBC-backed store for idempotency claims and completed responses. */
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

    /** Claims the key atomically; false means another request already holds or completed it. */
    public boolean claim(String key, String requestHash) {
        int inserted = jdbcTemplate.update("""
                insert into idempotency_key (idem_key, request_hash, created_at)
                values (?, ?, ?) on conflict (idem_key) do nothing
                """, key, requestHash, java.sql.Timestamp.from(clock.instant()));
        return inserted == 1;
    }

    public Optional<StoredResponse> find(String key) {
        return jdbcTemplate.query("""
                        select request_hash, response_status, response_body, content_type
                        from idempotency_key where idem_key = ?
                        """,
                        (rs, rowNum) -> new StoredResponse(
                                rs.getString("request_hash"),
                                rs.getObject("response_status", Integer.class),
                                rs.getString("response_body"),
                                rs.getString("content_type")),
                        key)
                .stream().findFirst();
    }

    public void complete(String key, int status, String body, String contentType) {
        jdbcTemplate.update("""
                update idempotency_key
                set response_status = ?, response_body = ?, content_type = ?
                where idem_key = ?
                """, status, body, contentType, key);
    }

    /** Frees the key after a failed execution so the client can retry. */
    public void release(String key) {
        jdbcTemplate.update("delete from idempotency_key where idem_key = ?", key);
    }

    public int purgeOlderThan(java.time.Duration retention) {
        return jdbcTemplate.update("delete from idempotency_key where created_at < ?",
                java.sql.Timestamp.from(clock.instant().minus(retention)));
    }
}
