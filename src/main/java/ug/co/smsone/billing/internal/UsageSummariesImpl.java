package ug.co.smsone.billing.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.billing.UsageSummaries;

/**
 * The {@link UsageSummaries} port: one aggregate over {@code api_usage_daily}. Plain JDBC like the
 * ledger's writer and exporter — the table is deliberately JPA-free (hot upsert path).
 */
@Component
class UsageSummariesImpl implements UsageSummaries {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    UsageSummariesImpl(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public UsageSummary lastDays(UUID orgId, int days) {
        int window = Math.clamp(days, 1, 90);
        // UTC day boundaries — the same convention the edge flushes and the exporter bills with.
        LocalDate to = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate from = to.minusDays(window - 1L);
        List<DailyUsage> byDay = jdbc.query("""
                select day, requests from api_usage_daily
                where org_id = ? and day between ? and ?
                order by day
                """,
                (rs, i) -> new DailyUsage(rs.getObject("day", LocalDate.class), rs.getLong("requests")),
                orgId, from, to);
        long total = byDay.stream().mapToLong(DailyUsage::requests).sum();
        return new UsageSummary(orgId, from, to, total, byDay);
    }
}
