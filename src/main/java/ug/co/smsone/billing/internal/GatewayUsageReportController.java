package ug.co.smsone.billing.internal;

import io.swagger.v3.oas.annotations.Hidden;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.security.GatewaySecretVerifier;

/**
 * The usage-report seam: the gateway flushes per-consumer request counts here every minute;
 * they land as per-org, per-day upserts in the api_usage_daily ledger. Internal (permit-listed
 * path, shared-secret authenticated, hidden from the spec) — the same contract as the other
 * gateway seams. Non-UUID consumers are skipped: only org-attributable usage can ever bill.
 */
@Hidden
@RestController
@RequestMapping("/internal/gateway/usage-report")
class GatewayUsageReportController {

    private final GatewaySecretVerifier secretVerifier;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    GatewayUsageReportController(GatewaySecretVerifier secretVerifier, JdbcTemplate jdbc, Clock clock) {
        this.secretVerifier = secretVerifier;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    record UsageReport(Map<String, Long> counts) {
    }

    @PostMapping
    void report(@RequestHeader(name = "X-Gateway-Secret", required = false) String presentedSecret,
            @RequestBody UsageReport report) {
        secretVerifier.verify(presentedSecret);
        if (report == null || report.counts() == null || report.counts().isEmpty()) {
            return;
        }
        Date day = Date.valueOf(LocalDate.now(clock));
        report.counts().forEach((consumer, requests) -> {
            UUID orgId = parseOrg(consumer);
            if (orgId == null || requests == null || requests <= 0) {
                return;
            }
            jdbc.update("""
                    insert into api_usage_daily (org_id, day, requests)
                    values (?, ?, ?)
                    on conflict (org_id, day)
                    do update set requests = api_usage_daily.requests + excluded.requests
                    """, orgId, day, requests);
        });
    }

    private static UUID parseOrg(String consumer) {
        try {
            return UUID.fromString(consumer);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
