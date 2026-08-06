package ug.co.smsone.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read port over the platform-side usage ledger ({@code api_usage_daily}, flushed from the edge)
 * for other protocol surfaces (the MCP module today). Local rows only — deliberately NOT the Kill
 * Bill proxy reads: an agent asking "where do I stand" should never hang on a billing vendor.
 */
public interface UsageSummaries {

    /** The trailing window ending today (UTC days, as metered). {@code days} is clamped to 1..90. */
    UsageSummary lastDays(UUID orgId, int days);

    record UsageSummary(UUID orgId, LocalDate from, LocalDate to, long totalRequests,
            List<DailyUsage> byDay) {
    }

    record DailyUsage(LocalDate day, long requests) {
    }
}
