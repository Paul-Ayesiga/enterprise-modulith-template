package ug.co.smsone.billing.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.billing.UsageSummaries;
import ug.co.smsone.shared.tenancy.CrossDatabaseWrites;

/**
 * The {@link UsageSummaries} port: one aggregate over {@code api_usage_daily}. Plain JDBC like the
 * ledger's writer and exporter — the table is deliberately JPA-free (hot upsert path).
 *
 * <h2>ADR 0011: the port already existed; what it was missing was WHICH DATABASE</h2>
 *
 * <p>{@code api_usage_daily} is platform-tier and stays on primary (ADR 0010 §2 — the export job's
 * cross-tenant {@code order by day} is the fairness property that per-tenant ledgers cannot express).
 * The two callers of this port are not: {@code McpToolDispatcher} pins the caller's organization before
 * the tool frame runs, and the org-scoped HTTP surface is pinned by {@code CurrentUserFilter}. So this
 * read has been issued on the tenant's connection, and for a tenant served from another database that
 * is {@code relation "platform.api_usage_daily" does not exist} — the usage tool 500s for exactly the
 * tenants whose isolation was the reason to move them.
 *
 * <p>Wrapped in {@link CrossDatabaseWrites#callOnPlatform}, which is the same connection and the same
 * (read-only) transaction whenever the caller is already co-located with primary. This is what §5.1's
 * remainder means by "a port with a declared authority": the CONTRACT does not change, the
 * implementation stops assuming that the caller's connection can see the authority's rows.
 */
@Component
class UsageSummariesImpl implements UsageSummaries {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CrossDatabaseWrites platformTier;

    UsageSummariesImpl(JdbcTemplate jdbc, Clock clock, CrossDatabaseWrites platformTier) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.platformTier = platformTier;
    }

    @Override
    public UsageSummary lastDays(UUID orgId, int days) {
        int window = Math.clamp(days, 1, 90);
        // UTC day boundaries — the same convention the edge flushes and the exporter bills with.
        LocalDate to = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate from = to.minusDays(window - 1L);
        List<DailyUsage> byDay = platformTier.callOnPlatform(() -> jdbc.query("""
                select day, requests from platform.api_usage_daily
                where org_id = ? and day between ? and ?
                order by day
                """,
                (rs, i) -> new DailyUsage(rs.getObject("day", LocalDate.class), rs.getLong("requests")),
                orgId, from, to));
        long total = byDay.stream().mapToLong(DailyUsage::requests).sum();
        return new UsageSummary(orgId, from, to, total, byDay);
    }
}
