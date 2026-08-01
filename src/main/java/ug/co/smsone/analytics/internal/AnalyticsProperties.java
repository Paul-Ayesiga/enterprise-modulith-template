package ug.co.smsone.analytics.internal;

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
        @DefaultValue("2") int maxEphemeralConcurrency) {
}
