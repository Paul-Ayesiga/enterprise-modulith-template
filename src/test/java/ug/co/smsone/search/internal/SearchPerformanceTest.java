package ug.co.smsone.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * "Lightning fast" as a NUMBER, not an adjective: 100k documents on the shared Postgres container,
 * 50 warm org-scoped FTS queries, p95 asserted under 50ms (GIN holds this comfortably at this
 * size; the test prints the measured figure so regressions have a baseline). Seeded with a fixed
 * random seed — the corpus is identical on every run.
 */
class SearchPerformanceTest extends AbstractIntegrationTest {

    private static final int DOCUMENTS = 100_000;
    private static final int ORGS = 20;
    private static final int QUERIES = 50;
    private static final long P95_BUDGET_MS = 50;

    private static final String[] WORDS = ("ledger revenue invoice quarterly report member churn "
            + "renewal onboarding audit export import delivery webhook token uganda kampala mobile "
            + "wallet reconciliation settlement statement compliance retention campaign broadcast")
            .split(" ");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SearchQueryService search;

    @Test
    void p95StaysUnderBudgetAcross100kDocuments() {
        Random random = new Random(42);
        UUID[] orgs = new UUID[ORGS];
        for (int i = 0; i < ORGS; i++) {
            orgs[i] = new UUID(0xCAFE, i); // deterministic
        }
        seed(random, orgs);

        // warm-up: planner, caches, JIT — one query PER ORG, or the measured loop pays each org's
        // cold first plan and the tail lies about steady state
        for (int i = 0; i < ORGS; i++) {
            search.search(orgs[i], false, null, WORDS[i % WORDS.length] + " " + WORDS[(i + 1) % WORDS.length],
                    new CursorPageRequest(20, null), hit -> hit);
        }

        List<Long> elapsed = new ArrayList<>(QUERIES);
        for (int i = 0; i < QUERIES; i++) {
            UUID org = orgs[random.nextInt(ORGS)];
            String q = WORDS[random.nextInt(WORDS.length)];
            long start = System.nanoTime();
            var result = search.search(org, false, null, q, new CursorPageRequest(20, null), hit -> hit);
            elapsed.add((System.nanoTime() - start) / 1_000_000);
            assertThat(result.items()).isNotEmpty(); // a query that matches nothing measures nothing
        }
        elapsed.sort(Long::compareTo);
        long p95 = elapsed.get((int) Math.ceil(QUERIES * 0.95) - 1);
        long p50 = elapsed.get(QUERIES / 2);
        System.out.printf("search over %,d docs: p50=%dms p95=%dms (budget %dms)%n",
                DOCUMENTS, p50, p95, P95_BUDGET_MS);
        assertThat(p95)
                .as("p95 over %d org-scoped FTS queries across %,d documents", QUERIES, DOCUMENTS)
                .isLessThan(P95_BUDGET_MS);
    }

    private void seed(Random random, UUID[] orgs) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from search_document where entity_type = 'perf'", Integer.class);
        if (existing != null && existing >= DOCUMENTS) {
            return; // already seeded by an earlier run in this container
        }
        // A REALISTIC corpus is mostly unique text with sparse shared terms — that selectivity is
        // exactly why FTS is fast. Each doc: two dictionary words (what the queries hit, ~400
        // matches per org per word) drowned in unique filler; all-dictionary bodies would make
        // every query rank thousands of rows and measure a corpus no real system has.
        List<Object[]> batch = new ArrayList<>(5_000);
        for (int i = 0; i < DOCUMENTS; i++) {
            StringBuilder body = new StringBuilder();
            body.append(WORDS[random.nextInt(WORDS.length)]).append(' ')
                    .append(WORDS[random.nextInt(WORDS.length)]);
            for (int w = 0; w < 10; w++) {
                body.append(" f").append(Long.toHexString(random.nextLong()));
            }
            String title = WORDS[random.nextInt(WORDS.length)] + " item " + i;
            batch.add(new Object[]{UUID.randomUUID(), orgs[i % ORGS], "perf", "perf-" + i, title, body.toString()});
            if (batch.size() == 5_000) {
                flush(batch);
            }
        }
        if (!batch.isEmpty()) {
            flush(batch);
        }
        // Fresh bulk load, stale planner statistics: without this the planner may pick sequential
        // scans over the GIN index and the measurement tests the wrong plan. Production relies on
        // autovacuum's analyze for the same effect.
        jdbc.execute("analyze search_document");
    }

    private void flush(List<Object[]> batch) {
        jdbc.batchUpdate("insert into search_document (id, org_id, entity_type, entity_id, title, body, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, now()) on conflict (entity_type, entity_id) do nothing",
                batch, batch.size(), (PreparedStatement ps, Object[] row) -> {
                    ps.setObject(1, row[0]);
                    ps.setObject(2, row[1]);
                    ps.setString(3, (String) row[2]);
                    ps.setString(4, (String) row[3]);
                    ps.setString(5, (String) row[4]);
                    ps.setString(6, (String) row[5]);
                });
        batch.clear();
    }
}
