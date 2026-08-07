package ug.co.smsone.analytics.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The curated analytics report surface (admin-only): a fixed catalog, and each report materialized
 * from Postgres into DuckDB then aggregated. Uses a private DuckDB file so it never contends with
 * {@code AnalyticsIntegrationTest} for the single-writer database lock.
 *
 * <p>The mart staleness budget is pinned to zero here, which is what the surface did before it had
 * one: these tests seed a row and then assert the report counts it, so they are read-your-writes
 * assertions about the endpoint, not about the refresh policy. The policy has its own test
 * ({@code AnalyticsMartTtlTest}) — leaving the default 15 minutes in place here would make these pass
 * or fail on suite ORDER, since a sibling test's earlier call would already have built the mart.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.analytics.database-path=build/test-analytics/analytics-api.duckdb",
        "app.analytics.snapshot-dir=build/test-analytics/snapshots-api",
        "app.analytics.mart-ttl=0s"
})
class AnalyticsApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static final RequestPostProcessor ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_platform-support"));

    @Test
    void catalogListsTheAvailableReports() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/reports").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='users-by-status')]").exists())
                .andExpect(jsonPath("$.data[?(@.id=='delivery-outcomes')]").exists());
    }

    @Test
    void runningAReportMaterializesAndAggregates() throws Exception {
        // Guarantee at least one row so the mart aggregation is non-empty regardless of suite order.
        seedPerson();

        mockMvc.perform(get("/api/v1/analytics/reports/users-by-status").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("analytics-report"))
                .andExpect(jsonPath("$.data.attributes.code").value("users-by-status"))
                .andExpect(jsonPath("$.data.attributes.rows").isArray())
                .andExpect(jsonPath("$.data.attributes.rows[0].status").exists())
                .andExpect(jsonPath("$.data.attributes.rows[0].total", Matchers.notNullValue()));
    }

    @Test
    void unknownReportIs404() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/reports/does-not-exist").with(ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/reports").with(jwt()))
                .andExpect(status().isForbidden());
    }

    /**
     * A report's {@code sourceSql} is executed as raw JDBC against Postgres, so
     * {@code @SQLRestriction("deleted_at is null")} — a Hibernate construct — does not apply to it.
     * Nothing else in the suite can catch that class of bug: the admin API and the report read the same
     * table through different machinery, and only the report counts rows the API has stopped showing.
     */
    @Test
    void softDeletedUsersAreExcludedFromTheReport() throws Exception {
        seedPerson();
        seedDeletedPerson();

        // The report materializes person at one instant and livePersonCount reads it at another;
        // an async person writer from a sibling test context sharing this container can land a
        // row between them and skew a GLOBAL count by ±1. Awaitility distinguishes that transient
        // skew (self-heals) from a real leak of soft-deleted rows (a STABLE divergence that never
        // converges, so this still fails). The guarantee under test — deleted rows don't leak — is
        // preserved; only the timing fragility is removed.
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            String body = mockMvc.perform(get("/api/v1/analytics/reports/users-by-status").with(ADMIN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            long reported = JsonPath.<List<Number>>read(body, "$.data.attributes.rows[*].total").stream()
                    .mapToLong(Number::longValue).sum();
            assertThat(reported).isEqualTo(livePersonCount());
        });
    }

    /**
     * The report counts {@code person} rows now — the identity is the person, and {@code status} moved
     * with it. Seeded through {@code EdgeSeed} so the row carries the provider link a provisioned person
     * really has; the subject and e-mail that used to sit on the row are its own tables' business, and
     * the report reads neither.
     */
    private void seedPerson() {
        EdgeSeed.person(jdbc, "analytics-" + UUID.randomUUID());
    }

    /** The same person, then soft-deleted — {@code deleted_at} is the one thing a live seed never sets. */
    private void seedDeletedPerson() {
        UUID personId = EdgeSeed.person(jdbc, "analytics-deleted-" + UUID.randomUUID());
        jdbc.update("update person set deleted_at = ? where id = ?", Timestamp.from(Instant.now()), personId);
    }

    private long livePersonCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from person where deleted_at is null", Long.class);
        return count == null ? 0 : count;
    }
}
