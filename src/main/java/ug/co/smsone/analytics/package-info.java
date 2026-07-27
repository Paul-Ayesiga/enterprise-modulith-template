/**
 * Analytics module: embedded DuckDB OLAP over data materialized from Postgres. Postgres stays the
 * system of record; heavy aggregations run in-process against DuckDB marts and Parquet snapshots.
 * The {@link ug.co.smsone.analytics.AnalyticsEngine} abstraction keeps future engines
 * (ClickHouse, Trino, Postgres fallback) a pure implementation swap.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Analytics")
package ug.co.smsone.analytics;
