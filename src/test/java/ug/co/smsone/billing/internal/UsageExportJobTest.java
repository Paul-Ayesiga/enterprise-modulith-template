package ug.co.smsone.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The export bridge against the real ledger: a closed, unexported day with an ACTIVE KB
 * subscription ships as a usage record with the idempotent trackingId and flips `exported`;
 * an org without a billable subscription is left for later, never lost.
 */
@TestPropertySource(properties = "app.billing.usage-export.enabled=true")
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
    void shipsClosedDaysIdempotentlyAndSkipsUnbillableOrgs() {
        UUID billable = UUID.randomUUID();
        UUID unbillable = UUID.randomUUID();
        UUID kbAccount = UUID.randomUUID();
        UUID kbSubscription = UUID.randomUUID();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        accounts.save(BillingAccount.link(billable, kbAccount));
        jdbc.update("insert into api_usage_daily (org_id, day, requests) values (?, ?, 4200), (?, ?, 7)",
                billable, Date.valueOf(yesterday), unbillable, Date.valueOf(yesterday));
        BDDMockito.given(killBill.subscriptions(kbAccount)).willReturn(List.of(
                new KillBillGateway.KbSubscription(kbSubscription.toString(), "pro-monthly", "ACTIVE")));

        job.run();

        BDDMockito.then(killBill).should().recordUsage(kbSubscription, "api-requests", yesterday,
                4200L, "usage-" + billable + "-" + yesterday);
        assertThat(jdbc.queryForObject(
                "select exported from api_usage_daily where org_id = ?", Boolean.class, billable)).isTrue();
        assertThat(jdbc.queryForObject(
                "select exported from api_usage_daily where org_id = ?", Boolean.class, unbillable))
                .as("no billable subscription — waits, is not dropped").isFalse();
    }
}
