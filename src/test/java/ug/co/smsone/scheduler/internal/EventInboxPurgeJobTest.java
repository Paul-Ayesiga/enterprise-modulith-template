package ug.co.smsone.scheduler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.events.EventInbox;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The inbox was the one durable table with no cleanup: one row per (listener, message) forever.
 * Dedup only needs to outlive the redelivery window, and this pins that rows inside it survive.
 */
class EventInboxPurgeJobTest extends AbstractIntegrationTest {

    @Autowired
    private EventInbox inbox;

    @Autowired
    private EventInboxPurgeJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purgesRowsPastRetentionAndKeepsTheDedupWindow() {
        String old = "old-" + UUID.randomUUID();
        String young = "young-" + UUID.randomUUID();
        inbox.recordIfNew("purge-probe", old);
        inbox.recordIfNew("purge-probe", young);
        jdbc.update("update event_inbox set processed_at = now() - interval '30 days' "
                + "where listener_id = 'purge-probe' and message_id = ?", old);

        job.purgeExpiredInboxRows();

        assertThat(count(old)).as("past retention goes").isZero();
        assertThat(count(young)).as("inside the dedup window stays — a redelivery must still dedupe")
                .isEqualTo(1);
    }

    private int count(String messageId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from event_inbox where listener_id = 'purge-probe' and message_id = ?",
                Integer.class, messageId);
        return n == null ? 0 : n;
    }
}
