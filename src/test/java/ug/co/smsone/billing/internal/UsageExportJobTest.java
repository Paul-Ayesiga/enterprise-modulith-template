package ug.co.smsone.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The export bridge against the real ledger. An org's whole closed backlog ships as ONE Kill Bill
 * request carrying every day, under a trackingId derived from that exact day set; an org without a
 * billable subscription is left for later, never lost; and days older than the export window are
 * left out of the run rather than being allowed to grow without bound in front of everything else.
 *
 * <p>The window is pinned here rather than inherited from the default so the last of those is a real
 * assertion about the property and not about whatever 45 days happens to mean today.
 *
 * <p><strong>The fixtures straddle the tiers the job straddles</strong> (ADR 0010 §2). The ledger is
 * platform-tier and is written {@code platform.api_usage_daily} here for the same reason the job
 * writes it that way — a qualified name is right from any axis, so the assertion cannot be quietly
 * reading a different schema than the run did. {@code billing_account} is the tenant's and is seeded
 * on that org's axis; the harness pins PLATFORM, which is honest for a test thread and cannot see it.
 */
@TestPropertySource(properties = {
        "app.billing.usage-export.enabled=true",
        "app.billing.usage-export.max-backlog-days=7"
})
class UsageExportJobTest extends AbstractIntegrationTest {

    @Autowired
    private UsageExportJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BillingAccountRepository accounts;

    @MockitoBean
    private KillBillGateway killBill;

    @Test
    void shipsAnOrgsWholeBacklogInOneCallAndSkipsUnbillableOrgs() {
        UUID billable = UUID.randomUUID();
        UUID unbillable = UUID.randomUUID();
        UUID kbSubscription = billableOrgWithActiveSubscription(billable);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate twoDaysAgo = yesterday.minusDays(1);
        LocalDate threeDaysAgo = yesterday.minusDays(2);
        usage(billable, threeDaysAgo, 11);
        usage(billable, twoDaysAgo, 7);
        usage(billable, yesterday, 4200);
        usage(unbillable, yesterday, 9);

        UsageExportJob.Export export = job.export();

        // ONE request for the three days, not three. Oldest first, because the batch inherits the
        // scan's `order by day` — and Kill Bill rates records in the order it is given them.
        ArgumentCaptor<String> trackingId = ArgumentCaptor.forClass(String.class);
        BDDMockito.then(killBill).should().recordUsage(eq(kbSubscription), eq("api-requests"),
                eq(List.of(new KillBillGateway.UsageDay(threeDaysAgo, 11L),
                        new KillBillGateway.UsageDay(twoDaysAgo, 7L),
                        new KillBillGateway.UsageDay(yesterday, 4200L))),
                trackingId.capture());
        assertThat(trackingId.getValue())
                .as("readable range for a support query, digest for correctness")
                .matches("usage-" + billable + "-" + threeDaysAgo + "-" + yesterday + "-[0-9a-f]{16}");
        assertThat(export.batches()).isEqualTo(1);
        assertThat(export.days()).isEqualTo(3);
        assertThat(exported(billable, threeDaysAgo)).isTrue();
        assertThat(exported(billable, twoDaysAgo)).isTrue();
        assertThat(exported(billable, yesterday)).isTrue();
        assertThat(exported(unbillable, yesterday))
                .as("no billable subscription — waits, is not dropped").isFalse();
    }

    /**
     * One closed day per org per night is the steady state, so the steady-state trackingId must be
     * the one the pre-batching job used: a day posted by the previous version can never come back
     * under a new id and be recorded twice.
     */
    @Test
    void aSingleDayBatchKeepsThePerDayTrackingId() {
        UUID billable = UUID.randomUUID();
        UUID kbSubscription = billableOrgWithActiveSubscription(billable);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        usage(billable, yesterday, 4200);

        job.export();

        BDDMockito.then(killBill).should().recordUsage(kbSubscription, "api-requests",
                List.of(new KillBillGateway.UsageDay(yesterday, 4200L)),
                "usage-" + billable + "-" + yesterday);
        assertThat(exported(billable, yesterday)).isTrue();
    }

    /**
     * The whole point of the window: unexportable days are the OLDEST by construction (they are
     * skipped nightly and nothing ever purges them), so an unbounded ascending scan grows forever and
     * a bare LIMIT over it would eventually return nothing else. Here the aged day is simply not in
     * the run — and, just as importantly, the recent day still is.
     */
    @Test
    void daysOlderThanTheExportWindowAreLeftOutOfTheRun() {
        UUID billable = UUID.randomUUID();
        UUID kbSubscription = billableOrgWithActiveSubscription(billable);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate longAgo = LocalDate.now().minusDays(30); // window is 7 days for this class
        usage(billable, longAgo, 999);
        usage(billable, yesterday, 4200);

        job.export();

        BDDMockito.then(killBill).should().recordUsage(kbSubscription, "api-requests",
                List.of(new KillBillGateway.UsageDay(yesterday, 4200L)),
                "usage-" + billable + "-" + yesterday);
        assertThat(exported(billable, yesterday)).isTrue();
        assertThat(exported(billable, longAgo))
                .as("outside the window: not sent, and so not marked either").isFalse();
    }

    /**
     * The batch key is the one thing a reviewer cannot check by reading the happy path: Kill Bill
     * drops a repeated trackingId WHOLESALE, so the key has to move with the day set and nothing
     * else. Pure function, exercised directly.
     */
    @Test
    void theBatchKeyIsAFunctionOfTheDaySetAndNothingElse() {
        UUID orgId = UUID.randomUUID();
        LocalDate first = LocalDate.of(2026, 3, 1);
        LocalDate second = LocalDate.of(2026, 3, 2);
        LocalDate third = LocalDate.of(2026, 3, 3);
        LocalDate fifth = LocalDate.of(2026, 3, 5);
        List<KillBillGateway.UsageDay> batch = List.of(new KillBillGateway.UsageDay(first, 1),
                new KillBillGateway.UsageDay(second, 2), new KillBillGateway.UsageDay(fifth, 5));

        assertThat(UsageExportJob.trackingId(orgId, batch))
                .as("same days, same key — a re-run of an identical batch must be a no-op at Kill Bill")
                .isEqualTo(UsageExportJob.trackingId(orgId, List.copyOf(batch)));
        assertThat(UsageExportJob.trackingId(orgId, List.of(new KillBillGateway.UsageDay(first, 9),
                new KillBillGateway.UsageDay(second, 9), new KillBillGateway.UsageDay(fifth, 9))))
                .as("amounts are outside the key: a corrected count re-posts as the same batch")
                .isEqualTo(UsageExportJob.trackingId(orgId, batch));
        assertThat(UsageExportJob.trackingId(orgId, List.of(new KillBillGateway.UsageDay(first, 1),
                new KillBillGateway.UsageDay(third, 3), new KillBillGateway.UsageDay(fifth, 5))))
                .as("same first day, last day and count — a range-only key would collide here and the "
                        + "differing day would be silently discarded")
                .isNotEqualTo(UsageExportJob.trackingId(orgId, batch));
        assertThat(UsageExportJob.trackingId(orgId, List.of(new KillBillGateway.UsageDay(first, 1))))
                .isEqualTo("usage-" + orgId + "-" + first);
    }

    /**
     * An org with a billing account and one ACTIVE Kill Bill subscription; returns its id.
     *
     * <p>The save runs on the org's own axis: {@code billing_account} is tenant-tier and the mapping
     * declares no schema, so on the harness's platform pin it would resolve inside {@code platform} and
     * fail on a table that plainly exists elsewhere. Seeding it where the job reads it is also what
     * makes the "unbillable org" case mean anything — that org gets no such row, deliberately.
     */
    private UUID billableOrgWithActiveSubscription(UUID orgId) {
        UUID kbAccount = UUID.randomUUID();
        UUID kbSubscription = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> accounts.save(BillingAccount.link(orgId, kbAccount)));
        BDDMockito.given(killBill.subscriptions(kbAccount)).willReturn(List.of(
                new KillBillGateway.KbSubscription(kbSubscription.toString(), "pro-monthly", "ACTIVE")));
        return kbSubscription;
    }

    private void usage(UUID orgId, LocalDate day, long requests) {
        jdbc.update("insert into platform.api_usage_daily (org_id, day, requests) values (?, ?, ?)",
                orgId, Date.valueOf(day), requests);
    }

    private boolean exported(UUID orgId, LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exported from platform.api_usage_daily where org_id = ? and day = ?",
                Boolean.class, orgId, Date.valueOf(day)));
    }
}
