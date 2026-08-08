package ug.co.smsone.analytics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import ug.co.smsone.analytics.AnalyticsEngine;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The report mart's refresh policy: rebuild from Postgres only once the mart is older than
 * {@code app.analytics.mart-ttl}, and never more than one rebuild of a report at a time.
 *
 * <p>Both properties are invisible from the HTTP surface — a stale answer and a fresh one are the
 * same JSON — so the service is driven directly with the REAL engine wrapped in a counter. The
 * wrapper is not a stand-in for infrastructure (ADR 0003 still holds: every call underneath reaches
 * DuckDB and Postgres); it only records how many refreshes happened and how many overlapped. Each
 * test builds its own service instance, so each starts from a cold mart and no test depends on the
 * order of the others.
 *
 * <p>Its own DuckDB file, like {@code AnalyticsApiTest}, so it never contends with
 * {@code AnalyticsIntegrationTest} for the single-writer database lock.
 */
@TestPropertySource(properties = {
        "app.analytics.database-path=build/test-analytics/analytics-ttl.duckdb",
        "app.analytics.snapshot-dir=build/test-analytics/snapshots-ttl"
})
class AnalyticsMartTtlTest extends AbstractIntegrationTest {

    @Autowired
    private AnalyticsEngine engine;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aMartInsideItsBudgetIsReusedInsteadOfRebuilt() {
        CountingEngine counting = new CountingEngine(engine, Duration.ZERO);
        AnalyticsReportService reports = serviceWith(Duration.ofMinutes(30), counting);

        EdgeSeed.person(jdbc, "ttl-" + UUID.randomUUID());
        long firstAnswer = total(reports.run(AnalyticsReport.PEOPLE_BY_STATUS));

        // The source moves under the mart...
        EdgeSeed.person(jdbc, "ttl-" + UUID.randomUUID());
        long secondAnswer = total(reports.run(AnalyticsReport.PEOPLE_BY_STATUS));

        assertThat(counting.builds.get()).as("one rebuild for both calls").isEqualTo(1);
        assertThat(secondAnswer).as("...and the report does not move with it, by design")
                .isEqualTo(firstAnswer);
        // Without this the test would also pass if the seed had silently done nothing: the point is
        // that the report is behind a source that really did change, not that nothing happened.
        assertThat(livePersonCount()).isGreaterThan(firstAnswer);
    }

    @Test
    void aZeroBudgetRebuildsOnEveryCall() {
        CountingEngine counting = new CountingEngine(engine, Duration.ZERO);
        AnalyticsReportService reports = serviceWith(Duration.ZERO, counting);

        long firstAnswer = total(reports.run(AnalyticsReport.PEOPLE_BY_STATUS));
        EdgeSeed.person(jdbc, "ttl-zero-" + UUID.randomUUID());
        long secondAnswer = total(reports.run(AnalyticsReport.PEOPLE_BY_STATUS));

        assertThat(counting.builds.get()).isEqualTo(2);
        assertThat(secondAnswer).as("zero budget is the always-current behaviour the surface had before")
                .isGreaterThan(firstAnswer);
    }

    /**
     * The burst that used to be the expensive one: several requests arriving at a cold (or just
     * expired) mart. Each of them wants a refresh, and a refresh copies a whole source table over its
     * own Postgres connection — so before the gate existed, ten concurrent report calls were ten
     * concurrent full-table copies against a 16-connection pool.
     */
    @Test
    void aBurstAtAColdMartCausesOneRebuild() throws Exception {
        CountingEngine counting = new CountingEngine(engine, Duration.ofMillis(200));
        AnalyticsReportService reports = serviceWith(Duration.ofMinutes(30), counting);

        runConcurrently(8, () -> reports.run(AnalyticsReport.DELIVERY_OUTCOMES));

        assertThat(counting.builds.get()).as("the burst collapses to a single copy").isEqualTo(1);
    }

    /**
     * The bound that survives even a zero budget, where every caller legitimately wants its own
     * refresh: they queue, they do not pile up. What is capped here is CONCURRENT copies — the thing
     * that reaches past this module into the connection pool — not the number of them.
     */
    @Test
    void refreshesOfOneReportNeverOverlap() throws Exception {
        CountingEngine counting = new CountingEngine(engine, Duration.ofMillis(100));
        AnalyticsReportService reports = serviceWith(Duration.ZERO, counting);

        runConcurrently(6, () -> reports.run(AnalyticsReport.PEOPLE_BY_STATUS));

        assertThat(counting.builds.get()).as("a zero budget really does refresh for each caller")
                .isEqualTo(6);
        assertThat(counting.peakInFlight.get()).as("but never two at once").isEqualTo(1);
    }

    /** Only {@code martTtl} is read from this record here; the rest configures the wrapped engine. */
    private AnalyticsReportService serviceWith(Duration martTtl, CountingEngine engine) {
        return new AnalyticsReportService(engine,
                new AnalyticsProperties("build/test-analytics/analytics-ttl.duckdb",
                        "build/test-analytics/snapshots-ttl", 2, "512MB", 2, martTtl),
                Clock.systemUTC());
    }

    /**
     * The burst, on real threads. Each caller declares the platform axis, because each one stands in
     * for a request and a request arrives with an axis already pinned by {@code CurrentUserFilter}
     * (ADR 0010 §3.4) — this pool has no filter above it, so an unpinned caller would route its
     * refresh to the empty {@code no_tenant} schema, the copy would throw, {@code builtAt} would stay
     * null, and every caller in the burst would rebuild. That failure looks exactly like the gate
     * being broken, which is the assertion below, so the axis has to be declared for the assertion to
     * mean what it says.
     */
    private static void runConcurrently(int callers, Runnable call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        TenantContext.runAsPlatform(call);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("every caller returned").isTrue();
        }
    }

    private long total(List<Map<String, Object>> rows) {
        return rows.stream().mapToLong(row -> ((Number) row.get("total")).longValue()).sum();
    }

    private long livePersonCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from person where deleted_at is null", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * The real engine plus a tally. {@code hold} keeps each refresh open a little longer than it
     * would otherwise be, so that if the service ever stopped serializing them the overlap would be
     * observed rather than merely possible.
     */
    private static final class CountingEngine implements AnalyticsEngine {

        private final AnalyticsEngine delegate;
        private final Duration hold;
        private final AtomicInteger builds = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peakInFlight = new AtomicInteger();

        CountingEngine(AnalyticsEngine delegate, Duration hold) {
            this.delegate = delegate;
            this.hold = hold;
        }

        @Override
        public long materializeFromPostgres(String sourceSql, String martTable) {
            builds.incrementAndGet();
            peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                long rows = delegate.materializeFromPostgres(sourceSql, martTable);
                Thread.sleep(hold.toMillis());
                return rows;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while holding a refresh open", e);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            return delegate.query(sql, params);
        }

        @Override
        public List<Map<String, Object>> queryEphemeral(String sql, Object... params) {
            return delegate.queryEphemeral(sql, params);
        }

        @Override
        public void execute(String sql, Object... params) {
            delegate.execute(sql, params);
        }

        @Override
        public Path exportParquet(String selectSql, String fileName) {
            return delegate.exportParquet(selectSql, fileName);
        }
    }
}
