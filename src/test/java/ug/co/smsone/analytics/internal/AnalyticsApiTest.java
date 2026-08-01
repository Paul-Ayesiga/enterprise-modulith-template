package ug.co.smsone.analytics.internal;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * The curated analytics report surface (admin-only): a fixed catalog, and each report materialized
 * from Postgres into DuckDB then aggregated. Uses a private DuckDB file so it never contends with
 * {@code AnalyticsIntegrationTest} for the single-writer database lock.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.analytics.database-path=build/test-analytics/analytics-api.duckdb",
        "app.analytics.snapshot-dir=build/test-analytics/snapshots-api"
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
        seedUser();

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
        seedUser();
        seedDeletedUser();

        String body = mockMvc.perform(get("/api/v1/analytics/reports/users-by-status").with(ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long reported = JsonPath.<List<Number>>read(body, "$.data.attributes.rows[*].total").stream()
                .mapToLong(Number::longValue).sum();
        assertThat(reported).isEqualTo(liveUserCount());
    }

    private void seedUser() {
        insertUser(null);
    }

    private void seedDeletedUser() {
        insertUser(Timestamp.from(Instant.now()));
    }

    private void insertUser(Timestamp deletedAt) {
        String subject = "kc-" + UUID.randomUUID();
        jdbc.update("""
                insert into app_user (id, subject, email, status, provisioned_at, version, created_at, deleted_at)
                values (?, ?, ?, 'INVITED', now(), 0, now(), ?)
                """, UUID.randomUUID(), subject, subject + "@smsone.co.ug", deletedAt);
    }

    private long liveUserCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from app_user where deleted_at is null", Long.class);
        return count == null ? 0 : count;
    }
}
