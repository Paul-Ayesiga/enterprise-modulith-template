package ug.co.smsone.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ug.co.smsone.settings.internal.SettingService;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 3A gate: an aggregate KPI runs over Postgres-born data via embedded DuckDB, with the
 * thread/memory caps applied, plus a native Parquet snapshot read back through DuckDB itself.
 */
class AnalyticsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SettingService settingService;

    @Autowired
    private AnalyticsEngine analytics;

    @Test
    void kpiOverPostgresDataViaDuckDb() throws Exception {
        settingService.put("kpi.a", "1", null);
        settingService.put("kpi.b", "2", null);
        settingService.put("kpi.c", "3", null);

        long materialized = analytics.materializeFromPostgres(
                "select setting_key, setting_value, created_at from setting", "mart_settings");
        assertThat(materialized).isGreaterThanOrEqualTo(3);

        // aggregate KPI in DuckDB
        List<Map<String, Object>> totals = analytics.query(
                "select count(*) as total, count(distinct setting_key) as distinct_keys from mart_settings");
        assertThat(totals).hasSize(1);
        assertThat(((Number) totals.getFirst().get("total")).longValue()).isEqualTo(materialized);

        // time-series style KPI (window/date functions are the point of an OLAP engine)
        List<Map<String, Object>> perDay = analytics.query("""
                select date_trunc('day', created_at) as day, count(*) as created
                from mart_settings group by 1 order by 1""");
        assertThat(perDay).isNotEmpty();

        // the caps that protect the app JVM are actually applied
        assertThat(analytics.query("select current_setting('threads') as t").getFirst().get("t"))
                .hasToString("2");
        assertThat(analytics.query("select current_setting('memory_limit') as m").getFirst().get("m")
                .toString()).isNotBlank();
    }

    @Test
    void parquetSnapshotRoundtrip() throws Exception {
        settingService.put("parquet.probe", "x", null);
        analytics.materializeFromPostgres(
                "select setting_key, created_at from setting", "mart_parquet");

        Path snapshot = analytics.exportParquet("select * from mart_parquet", "settings.parquet");
        assertThat(Files.exists(snapshot)).isTrue();
        assertThat(Files.size(snapshot)).isPositive();

        // read the snapshot back through DuckDB's native Parquet reader on a throwaway DB
        List<Map<String, Object>> fromParquet = analytics.queryEphemeral(
                "select count(*) as c from read_parquet('" + snapshot.toString().replace("'", "''") + "')");
        assertThat(((Number) fromParquet.getFirst().get("c")).longValue()).isPositive();
    }

    @Test
    void ephemeralQueriesRunOnAThrowawayDatabase() {
        List<Map<String, Object>> answer = analytics.queryEphemeral("select 41 + 1 as answer");
        assertThat(((Number) answer.getFirst().get("answer")).intValue()).isEqualTo(42);
    }
}
