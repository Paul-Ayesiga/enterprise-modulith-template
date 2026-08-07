package ug.co.smsone.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The retention the V24 terminal index was waiting for: old terminal jobs go (their error rows
 * cascading), queued work and everything inside the window stays — and the purge reports its work.
 */
class ExchangeRetentionJobTest extends AbstractIntegrationTest {

    @Autowired
    private ExchangeRetentionJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private double purged() {
        io.micrometer.core.instrument.Counter counter = meters.find("smsone.purge.deleted")
                .tag("job", "exchange-job-retention").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void purgesOldTerminalJobsAndTheirErrorRowsAndNothingElse() {
        double purgedBefore = purged();
        UUID orgId = UUID.randomUUID();
        UUID oldCompleted = insert(orgId, "COMPLETED", "40 days");
        UUID oldFailed = insert(orgId, "FAILED", "40 days");
        UUID oldPending = insert(orgId, "PENDING", "40 days");
        UUID youngCompleted = insert(orgId, "COMPLETED", "1 day");
        jdbc.update("insert into exchange_job_error (job_id, row_num, error) values (?, 1, 'x')", oldCompleted);

        // purgeExpiredJobs() is @SchedulerLock'd and the advice fires on a direct call too, so a
        // sibling test that already ran the purge in this context would still hold the 30-minute
        // lease and this call would silently do nothing. Release it first, as the override test does.
        jdbc.update("update shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "exchange-job-retention");
        job.purgeExpiredJobs();

        assertThat(exists(oldCompleted)).as("terminal past retention goes").isFalse();
        assertThat(exists(oldFailed)).as("dead jobs age out too — the log is not an archive").isFalse();
        assertThat(exists(oldPending)).as("queued work is never retention's business").isTrue();
        assertThat(exists(youngCompleted)).as("inside the window stays").isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from exchange_job_error where job_id = ?", Integer.class, oldCompleted))
                .as("error rows cascade with their job").isZero();
        assertThat(purged()).isEqualTo(purgedBefore + 2);
    }

    /**
     * The requester is a {@code person.id} now, not the subject string it used to be — the column is
     * {@code requester_person_id uuid}. The purge never reads it and there is no FK to {@code person},
     * so a bare id is enough; it only has to be a UUID and non-null.
     */
    private UUID insert(UUID orgId, String status, String age) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into exchange_job (id, org_id, requester_person_id, job_type, handler, "
                + "handler_version, format, status, created_at) "
                + "values (?, ?, ?, 'IMPORT', 'test-counter', 1, 'CSV', ?, now() - interval '" + age + "')",
                id, orgId, UUID.randomUUID(), status);
        return id;
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from exchange_job where id = ?)", Boolean.class, id));
    }
}
