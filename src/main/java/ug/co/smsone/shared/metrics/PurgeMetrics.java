package ug.co.smsone.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * One name and tag set for every retention purge, so a single alert can watch them all: a purge
 * that goes silent (no increments across its schedule) is the failure mode the counter exists to
 * expose — the jobs already log, but nobody alerts on a log line that didn't happen.
 */
public final class PurgeMetrics {

    private PurgeMetrics() {
    }

    /** Records {@code deleted} rows removed by {@code job} from {@code table}; zero is not recorded. */
    public static void purged(MeterRegistry meters, String job, String table, long deleted) {
        if (deleted <= 0) {
            return;
        }
        Counter.builder("smsone.purge.deleted")
                .description("Rows removed by retention purges")
                .tag("job", job)
                .tag("table", table)
                .register(meters)
                .increment(deleted);
    }
}
