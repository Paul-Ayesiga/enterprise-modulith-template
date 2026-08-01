package ug.co.smsone.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The scheduled replacement for the worker's old in-loop purge (which ran unlocked on every
 * instance and swallowed failures). Terminal rows past the window go; queued work stays.
 */
class NotificationRetentionJobTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationRetentionJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purgesOldTerminalRowsAndNothingElse() {
        UUID oldSent = insert("SENT", "10 days");
        UUID oldFailed = insert("FAILED", "10 days");
        UUID oldPending = insert("PENDING", "10 days");
        UUID youngSent = insert("SENT", "1 day");

        job.purgeExpiredDeliveries();

        assertThat(exists(oldSent)).as("SENT past retention goes").isFalse();
        assertThat(exists(oldFailed)).as("dead-letters age out too, matching the webhook queue").isFalse();
        assertThat(exists(oldPending)).as("queued work is never retention's business").isTrue();
        assertThat(exists(youngSent)).as("inside the window stays").isTrue();
    }

    private UUID insert(String status, String age) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into notification_delivery (id, channel, recipient, subject, body, status, "
                        + "attempts, max_attempts, next_attempt_at, created_at) "
                        + "values (?, 'EMAIL', 'retention-probe@smsone.co.ug', 'probe', '', ?, 1, 5, now(), "
                        + "now() - interval '" + age + "')",
                id, status);
        return id;
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from notification_delivery where id = ?)", Boolean.class, id));
    }
}
