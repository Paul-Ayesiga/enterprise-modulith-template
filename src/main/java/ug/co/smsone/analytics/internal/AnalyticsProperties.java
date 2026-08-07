package ug.co.smsone.analytics.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DuckDB runs in-process — the caps exist so analytics can never starve the application JVM.
 */
@ConfigurationProperties(prefix = "app.analytics")
record AnalyticsProperties(
        @DefaultValue("data/analytics.duckdb") String databasePath,
        @DefaultValue("data/snapshots") String snapshotDir,
        @DefaultValue("2") int threads,
        @DefaultValue("512MB") String memoryLimit,
        @DefaultValue("2") int maxEphemeralConcurrency,
        // How stale a report's mart may be before the next call rebuilds it from Postgres. It is a
        // staleness budget, not a cache size: a rebuild copies the whole source table, so this is what
        // decides how often that copy happens. Zero means rebuild on every call — correct, and the
        // cost that made this property necessary. See AnalyticsReportService.
        @DefaultValue("15m") Duration martTtl) {

    AnalyticsProperties {
        if (martTtl == null || martTtl.isNegative()) {
            // Fails at startup rather than serving something nobody chose: a negative budget is not
            // "always fresh" (that is zero) — it is a typo, and the two look identical at runtime.
            throw new IllegalStateException(
                    "app.analytics.mart-ttl must not be negative (0 means refresh on every call), was "
                            + martTtl);
        }
    }
}
