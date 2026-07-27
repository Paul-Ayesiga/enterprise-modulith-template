package ug.co.smsone.analytics;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * OLAP seam for dashboards/KPIs/reports. SQL passed here is developer-authored analytics SQL —
 * never end-user input.
 */
public interface AnalyticsEngine {

    /** Runs an aggregate query against the durable mart database. */
    List<Map<String, Object>> query(String sql, Object... params);

    /** Runs a query on a throwaway in-memory database (same resource caps, no persistence). */
    List<Map<String, Object>> queryEphemeral(String sql, Object... params);

    /** DDL/DML against the durable mart database. */
    void execute(String sql, Object... params);

    /**
     * Materializes a mart: streams the rows of {@code sourceSql} (run against Postgres) into the
     * DuckDB table {@code martTable}, replacing it. Returns the row count.
     */
    long materializeFromPostgres(String sourceSql, String martTable);

    /** Native Parquet snapshot of a mart query; returns the written file. */
    Path exportParquet(String selectSql, String fileName);
}
