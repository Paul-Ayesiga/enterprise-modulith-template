package ug.co.smsone.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The retention override, end to end against the real purge SQL: an org with a long
 * {@code EXCHANGE_JOB} retention keeps its terminal jobs past the 30-day platform default, while an
 * org without one has them purged. The default default is 30 days; the seeded rows are 400 days old.
 */
class ExchangeRetentionOverrideTest extends AbstractIntegrationTest {

    @Autowired
    private ExchangeRetentionJob retentionJob;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anOrgRetentionOverrideKeepsThatOrgsTerminalJobsPastTheDefaultCutoff() {
        UUID kept = UUID.randomUUID();
        UUID purged = UUID.randomUUID();
        UUID keptJob = insertOldTerminalJob(kept);
        UUID purgedJob = insertOldTerminalJob(purged);
        // 'kept' retains its exchange-job rows for 3650 days; the platform default is 30.
        jdbc.update("insert into org_retention_override (id, org_id, scope, retention_days, version, created_at) "
                + "values (?, ?, 'EXCHANGE_JOB', 3650, 0, now())", UUID.randomUUID(), kept);

        jdbc.update("update shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "exchange-job-retention");
        retentionJob.purgeExpiredJobs();

        assertThat(exists(keptJob)).as("overridden org's 400-day-old job survives").isTrue();
        assertThat(exists(purgedJob)).as("non-overridden org's 400-day-old job is purged").isFalse();
    }

    private UUID insertOldTerminalJob(UUID orgId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into exchange_job (id, org_id, requester, job_type, handler, format, status, created_at) "
                + "values (?, ?, 'tester', 'EXPORT', 'noop', 'CSV', 'COMPLETED', now() - interval '400 days')",
                id, orgId);
        return id;
    }

    private boolean exists(UUID jobId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from exchange_job where id = ?", Integer.class, jobId);
        return count != null && count > 0;
    }
}
