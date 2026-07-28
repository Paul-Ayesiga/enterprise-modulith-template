package ug.co.smsone.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.notification.NotificationChannel;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Queue semantics against REAL Postgres: fenced status updates (a stale claimant can never corrupt a
 * re-claimed row), unknown-channel quarantine, and continuous-throttle tracking.
 */
class NotificationDeliveryQueueTest extends AbstractIntegrationTest {

    private static final Duration WIDE = Duration.ofMinutes(5);

    @Autowired
    private NotificationDeliveryQueue queue;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetQueue() {
        // The singleton Postgres container is shared across every test class; other classes (e.g. any
        // feature-flag toggle) leave rows in this global queue and claim() is FIFO across all of them.
        jdbc.update("delete from notification_delivery");
    }

    @Test
    void staleClaimantsUpdatesAreFencedOutAfterReclaim() {
        UUID id = enqueueOne("fence-" + UUID.randomUUID());

        ClaimedDelivery first = claimOne(id, WIDE);
        assertThat(first.attempts()).isEqualTo(1);

        // Simulate the first worker going stale, then a second worker reclaiming the row.
        jdbc.update("update notification_delivery set locked_at = now() - interval '1 hour' where id = ?", id);
        ClaimedDelivery second = claimOne(id, WIDE);
        assertThat(second.attempts()).isEqualTo(2);

        // The stale worker finally finishes and reports: its fence (attempts=1) no longer matches.
        queue.markSent(id, first.attempts());
        assertThat(statusOf(id)).isEqualTo("PROCESSING"); // untouched — still the new owner's row

        queue.markSent(id, second.attempts());
        assertThat(statusOf(id)).isEqualTo("SENT"); // the live owner's update lands
    }

    @Test
    void unknownChannelRowIsDeadLetteredInsteadOfPoisoningTheBatch() {
        UUID poisoned = UUID.randomUUID();
        String subject = "poison-" + UUID.randomUUID();
        jdbc.update("""
                insert into notification_delivery
                    (id, channel, recipient, subject, body, status, attempts, max_attempts, next_attempt_at, created_at)
                values (?, 'CARRIER_PIGEON', 'someone', ?, 'b', 'PENDING', 0, 3, now(), now())
                """, poisoned, subject);
        UUID healthy = enqueueOne("healthy-" + UUID.randomUUID());

        List<ClaimedDelivery> claimed = queue.claim(100, WIDE);

        assertThat(claimed).extracting(ClaimedDelivery::id).contains(healthy).doesNotContain(poisoned);
        assertThat(statusOf(poisoned)).isEqualTo("FAILED");
    }

    @Test
    void throttledSinceTracksTheContinuousThrottleStretchAndClearsOnRealAttempt() {
        UUID id = enqueueOne("throttle-" + UUID.randomUUID());

        ClaimedDelivery first = claimOne(id, WIDE);
        assertThat(first.throttledSince()).isNull();
        queue.rescheduleThrottled(id, Instant.now(), first.attempts());

        // Attempts refunded (throttling is not a try) and the stretch start recorded.
        assertThat(attemptsOf(id)).isZero();
        Timestamp since = jdbc.queryForObject(
                "select throttled_since from notification_delivery where id = ?", Timestamp.class, id);
        assertThat(since).isNotNull();

        // Still throttled on the next claim: the ORIGINAL stretch start is preserved.
        ClaimedDelivery again = claimOne(id, WIDE);
        assertThat(again.throttledSince()).isEqualTo(since.toInstant());
        queue.rescheduleThrottled(id, Instant.now(), again.attempts());

        // A real (failed) attempt ends the throttled stretch.
        ClaimedDelivery attempt = claimOne(id, WIDE);
        queue.reschedule(id, Instant.now(), "boom", attempt.attempts());
        assertThat(jdbc.queryForObject(
                "select throttled_since from notification_delivery where id = ?", Timestamp.class, id)).isNull();
    }

    // ---- helpers ----

    private UUID enqueueOne(String subject) {
        queue.enqueue(List.of(new NewDelivery(NotificationChannel.WEBHOOK, "http://example.invalid", subject, "b")), 3);
        return jdbc.queryForObject("select id from notification_delivery where subject = ?", UUID.class, subject);
    }

    /** Claim broadly, then return the one row under test (other tests' rows may ride along). */
    private ClaimedDelivery claimOne(UUID id, Duration staleLock) {
        return queue.claim(500, staleLock).stream()
                .filter(delivery -> delivery.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("row " + id + " was not claimed"));
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from notification_delivery where id = ?", String.class, id);
    }

    private int attemptsOf(UUID id) {
        Integer attempts = jdbc.queryForObject(
                "select attempts from notification_delivery where id = ?", Integer.class, id);
        return attempts == null ? -1 : attempts;
    }
}
