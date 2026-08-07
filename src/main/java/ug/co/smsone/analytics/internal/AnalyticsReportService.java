package ug.co.smsone.analytics.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;
import ug.co.smsone.analytics.AnalyticsEngine;

/**
 * Runs a catalog report: refresh its mart from Postgres if the mart has gone stale, then return the
 * aggregate rows.
 *
 * <p><b>Why a staleness budget and not a refresh per call.</b> A refresh re-materializes the report's
 * WHOLE source table into DuckDB before aggregating it — 300,000 notification_delivery rows moved to
 * answer with four, 6-11 seconds of wall clock per request. Doing that once per HTTP call had three
 * costs, and the third is the one that reaches beyond this module: the copy streams under an open
 * Postgres read transaction (a cursor needs one), so for its whole duration a connection sits idle in
 * transaction with a fixed {@code backend_xmin} pinning the global vacuum horizon — every table's,
 * not just the one being read. {@code app.analytics.mart-ttl} turns "per request" into "at most once
 * per budget", which is the only lever that shortens the pin without changing how the copy works.
 *
 * <p>Pure pushdown — aggregating in Postgres and skipping DuckDB — is faster still and is deliberately
 * NOT what this does: the mart is the only production exercise of the OLAP engine ADR 0006 exists for,
 * and a template that ships an engine nothing uses has documented a choice it never made.
 *
 * <p><b>The freshness clock is per instance, and that is not an oversight.</b> The mart is a local
 * DuckDB file, so each replica has its own; a shared timestamp would let one instance decide a mart
 * it never built is fresh. A restart therefore costs one rebuild per report: the file survives, but
 * nothing on it records when it was made, and assuming is how a deploy starts serving last week's
 * numbers.
 */
@Service
class AnalyticsReportService {

    private final AnalyticsEngine engine;
    private final AnalyticsProperties properties;
    private final Clock clock;
    private final Map<AnalyticsReport, Mart> marts = new ConcurrentHashMap<>();

    AnalyticsReportService(AnalyticsEngine engine, AnalyticsProperties properties, Clock clock) {
        this.engine = engine;
        this.properties = properties;
        this.clock = clock;
    }

    /** One report's refresh state: when its mart was last built, and the gate that builds it. */
    private static final class Mart {
        private final ReentrantLock refresh = new ReentrantLock();
        private volatile Instant builtAt; // null until the first successful build
    }

    List<Map<String, Object>> run(AnalyticsReport report) {
        refreshIfStale(report);
        return engine.query(report.martQuery());
    }

    /**
     * At most one refresh per report is ever in flight. That bound is load-bearing rather than tidy:
     * {@code materializeFromPostgres} takes a Postgres connection of its own and sits outside the
     * engine's lock and its ephemeral-query semaphore, so before this gate existed N concurrent report
     * requests meant N concurrent full-table copies against a 16-connection pool — an admin holding
     * the refresh key down could starve the whole application of connections.
     *
     * <p>Callers that arrive during a refresh WAIT for it instead of being served the mart it is
     * replacing. They asked for data no older than the budget and the budget has expired; handing back
     * the stale copy would make the guarantee depend on how many people asked at once.
     */
    private void refreshIfStale(AnalyticsReport report) {
        Mart mart = marts.computeIfAbsent(report, r -> new Mart());
        if (isFresh(mart)) {
            return;
        }
        mart.refresh.lock();
        try {
            if (isFresh(mart)) {
                return; // rebuilt while we queued — one copy for the whole burst, not one each
            }
            engine.materializeFromPostgres(report.sourceSql(), report.martTable());
            // Stamped AFTER the swap: a failed refresh leaves the previous mart in place (the engine
            // guarantees that), and leaving the old stamp with it is what makes the next call retry
            // rather than serve stale rows under a fresh timestamp.
            mart.builtAt = clock.instant();
        } finally {
            mart.refresh.unlock();
        }
    }

    /** Never fresh before the first build, and never fresh at all when the budget is zero. */
    private boolean isFresh(Mart mart) {
        Instant builtAt = mart.builtAt;
        return builtAt != null
                && builtAt.plus(properties.martTtl()).isAfter(clock.instant());
    }
}
