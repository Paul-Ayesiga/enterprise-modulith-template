package ug.co.smsone.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The retention override, end to end against the real purge SQL: an org with a long
 * {@code EXCHANGE_JOB} retention keeps its terminal jobs past the 30-day platform default, while an
 * org without one has them purged. The default default is 30 days; the seeded rows are 400 days old.
 *
 * <p><strong>Every row this class writes and reads is the tenant's, so every one of them is written
 * and read on that org's axis</strong> (ADR 0010 §2). {@code org_retention_override} is tenant-tier
 * outright; {@code exchange_job} is one of the seven SPLIT tables, where a non-null {@code org_id}
 * means the tenant's copy and bare-on-a-tenant-axis is the address that reaches it. Both matter to the
 * assertion: {@code RetentionPurges} reads the override before it considers a single row, and
 * {@code purgeTerminalBatchForOrg} routes on {@code org_id} inside the job's own tenant span — so a
 * fixture left on the harness's platform pin would either fail outright or seed the copy the purge
 * never looks at and "survive" a run that never saw it.
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
        // 'kept' retains its exchange-job rows for 3650 days; the platform default is 30. On that org's
        // own axis: the retention contract is the tenant's row, which is the whole reason the job takes
        // a tenant span to read it.
        TenantContext.runAs(kept, () -> jdbc.update(
                "insert into org_retention_override (id, org_id, scope, retention_days, version, created_at) "
                        + "values (?, ?, 'EXCHANGE_JOB', 3650, 0, now())", UUID.randomUUID(), kept));

        // `shedlock` is platform-tier and bare here on the harness's platform pin, which is where it
        // resolves.
        jdbc.update("update shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "exchange-job-retention");
        retentionJob.purgeExpiredJobs();

        assertThat(exists(kept, keptJob)).as("overridden org's 400-day-old job survives").isTrue();
        assertThat(exists(purged, purgedJob)).as("non-overridden org's 400-day-old job is purged").isFalse();
    }

    /**
     * The requester is a {@code person.id} now, not the subject string it used to be — the column is
     * {@code requester_person_id uuid}. Nothing in the purge reads it, so an unlinked id is enough here
     * (there is no FK to {@code person}); what matters is that it is a UUID and non-null.
     */
    private UUID insertOldTerminalJob(UUID orgId) {
        UUID id = UUID.randomUUID();
        // Bare, on the org's axis — the tenant copy of the split table (ADR 0010 §2 row 10), which is
        // the address that keeps working once the org is promoted to a silo. Naming tenant_pool here
        // would seed the right rows today and the wrong schema the day Phase 5 lands.
        TenantContext.runAs(orgId, () -> jdbc.update(
                "insert into exchange_job (id, org_id, requester_person_id, job_type, handler, format, "
                        + "status, created_at) "
                        + "values (?, ?, ?, 'EXPORT', 'noop', 'CSV', 'COMPLETED', now() - interval '400 days')",
                id, orgId, UUID.randomUUID()));
        return id;
    }

    /** Takes the org as well as the job: the row's home is the org's, and only its axis reaches it. */
    private boolean exists(UUID orgId, UUID jobId) {
        Integer count = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from exchange_job where id = ?", Integer.class, jobId));
        return count != null && count > 0;
    }
}
