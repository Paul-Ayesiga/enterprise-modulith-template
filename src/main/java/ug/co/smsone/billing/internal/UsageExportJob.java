package ug.co.smsone.billing.internal;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The metered-billing bridge: closed days from the api_usage_daily ledger become Kill Bill usage
 * records on the org's ACTIVE subscription. Opt-in (BILLING_USAGE_EXPORT) because it only prices
 * when the KB catalog defines the unit — a simple-plan catalog silently records nothing billable.
 * Double-billing is impossible twice over: the `exported` flag here, KB's trackingId dedup there.
 * Per-org failures log and continue; the unexported row waits for the next night.
 */
@Component
@ConditionalOnProperty(name = "app.billing.usage-export.enabled", havingValue = "true")
class UsageExportJob {

    private static final Logger log = LoggerFactory.getLogger(UsageExportJob.class);

    private final JdbcTemplate jdbc;
    private final BillingAccountRepository accounts;
    private final KillBillGateway killBill;
    private final String unitType;

    UsageExportJob(JdbcTemplate jdbc, BillingAccountRepository accounts, KillBillGateway killBill,
            @Value("${app.billing.usage-export.unit:api-requests}") String unitType) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.killBill = killBill;
        this.unitType = unitType;
    }

    record UsageRow(UUID orgId, LocalDate day, long requests) {
    }

    @Scheduled(cron = "${app.scheduler.usage-export-cron:0 20 4 * * *}")
    @SchedulerLock(name = "billing-usage-export", lockAtMostFor = "PT30M")
    public void run() {
        List<UsageRow> rows = jdbc.query("""
                select org_id, day, requests from api_usage_daily
                where exported = false and day < current_date
                order by day
                """, (rs, i) -> new UsageRow(rs.getObject("org_id", UUID.class),
                rs.getDate("day").toLocalDate(), rs.getLong("requests")));
        // Resolved once per ORG, not once per (org, day). The ledger is keyed org x day, so an org
        // with a thirty-day backlog used to make thirty identical billing_account reads and — the
        // expensive half — thirty identical Kill Bill round trips for one answer. computeIfAbsent
        // stores only successful resolutions: a remote failure propagates to the per-row catch below
        // and the next row of the same org retries it, exactly as before.
        Map<UUID, Optional<UUID>> billableSubscriptions = new HashMap<>();
        int shipped = 0;
        for (UsageRow row : rows) {
            try {
                UUID subscriptionId = billableSubscriptions
                        .computeIfAbsent(row.orgId(), this::activeSubscriptionOf)
                        .orElse(null);
                if (subscriptionId == null) {
                    continue; // no billable subscription — leave the row for when there is one
                }
                killBill.recordUsage(subscriptionId, unitType, row.day(), row.requests(),
                        "usage-" + row.orgId() + "-" + row.day());
                jdbc.update("update api_usage_daily set exported = true where org_id = ? and day = ?",
                        row.orgId(), Date.valueOf(row.day()));
                shipped++;
            } catch (RuntimeException e) {
                log.warn("Usage export for org {} day {} failed — will retry next run: {}",
                        row.orgId(), row.day(), e.toString());
            }
        }
        if (shipped > 0) {
            log.info("Exported {} usage day(s) to Kill Bill as unit '{}'", shipped, unitType);
        }
    }

    /** The org's one billable subscription, or empty when it has no billing account or none ACTIVE. */
    private Optional<UUID> activeSubscriptionOf(UUID orgId) {
        return accounts.findByOrgId(orgId)
                .map(account -> killBill.subscriptions(account.getKbAccountId()))
                .flatMap(subs -> subs.stream()
                        .filter(sub -> "ACTIVE".equalsIgnoreCase(sub.state()))
                        .findFirst())
                .map(sub -> UUID.fromString(sub.subscriptionId()));
    }
}
