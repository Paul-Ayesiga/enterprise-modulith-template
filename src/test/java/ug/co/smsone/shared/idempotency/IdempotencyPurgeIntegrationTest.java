package ug.co.smsone.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The purge used to be one DELETE across the whole retention window: every expired key's row lock
 * taken in a single transaction and held until it committed, with {@link IdempotencyStore#claim}
 * queueing behind each one. This pins the batching that replaced it — a batch stops at its LIMIT,
 * and the loop over batches still clears the whole window.
 */
class IdempotencyPurgeIntegrationTest extends AbstractIntegrationTest {

    private static final String PRINCIPAL = "purge-probe:" + UUID.randomUUID();

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aBatchStopsAtItsLimitAndTheLoopFinishesTheWindow() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        // Every context in the run shares one Postgres, so clear the expired window first: another
        // test's back-dated key would be swept into this batch and shift the counts below.
        jdbc.update("delete from idempotency_key where created_at < ?", Timestamp.from(cutoff));
        seedExpired(IdempotencyStore.PURGE_BATCH_SIZE + 1, cutoff.minus(Duration.ofDays(1)));
        store.claim(PRINCIPAL, "still-replayable", "hash", Duration.ofMinutes(5));

        int firstBatch = store.purgeBatch(Timestamp.from(cutoff));

        assertThat(firstBatch)
                .as("the LIMIT is what keeps one statement's lock set small — a batch never takes the "
                        + "whole window")
                .isEqualTo(IdempotencyStore.PURGE_BATCH_SIZE);
        assertThat(expiredKeysLeft()).isEqualTo(1);

        int purged = store.purgeOlderThan(Duration.ofDays(1));

        assertThat(purged).as("the loop keeps going until a short batch").isEqualTo(1);
        assertThat(expiredKeysLeft()).isZero();
        assertThat(keysFor(PRINCIPAL))
                .as("a key inside retention stays replayable — the purge window is the only thing "
                        + "that decides, not the batch boundary")
                .isEqualTo(1);
    }

    private void seedExpired(int count, Instant createdAt) {
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {PRINCIPAL + ":expired", "key-" + i, "hash", Timestamp.from(createdAt)});
        }
        jdbc.batchUpdate("insert into idempotency_key (principal, idem_key, request_hash, created_at) "
                + "values (?, ?, ?, ?)", rows);
    }

    private int expiredKeysLeft() {
        return keysFor(PRINCIPAL + ":expired");
    }

    private int keysFor(String principal) {
        Integer count = jdbc.queryForObject("select count(*) from idempotency_key where principal = ?",
                Integer.class, principal);
        return count == null ? 0 : count;
    }
}
