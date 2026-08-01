package ug.co.smsone.analytics.internal;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fixed catalog of curated reports. Each carries **developer-authored** SQL only (never end-user
 * input, per the {@code AnalyticsEngine} contract): a Postgres {@code sourceSql} materialized into the
 * DuckDB mart {@code martTable}, then an aggregate {@code martQuery} run against that mart.
 *
 * <p><b>{@code sourceSql} runs as raw JDBC, so {@code @SQLRestriction} does not apply.</b> A report
 * over a soft-deletable table must filter {@code deleted_at is null} itself, or it silently disagrees
 * with the admin API reading the same table — and the report is the one used for headcounts and
 * licence reconciliation. The soft-deletable tables are {@code setting}, {@code feature_flag},
 * {@code app_user}, {@code organization}, {@code org_role}, {@code membership},
 * {@code webhook_subscription}, {@code translation}, {@code document}, {@code exchange_schedule},
 * {@code org_subscription}, {@code billing_account}, {@code user_profile}, {@code api_key}, {@code org_group}, {@code user_device}, {@code org_security_policy}, {@code integration}, {@code maintenance_window} and {@code ticket}; {@code notification_delivery} is not one of them.
 */
enum AnalyticsReport {

    USERS_BY_STATUS("users-by-status",
            "Provisioned users grouped by lifecycle status",
            "select status from app_user where deleted_at is null",
            "mart_users_by_status",
            "select status, count(*) as total from mart_users_by_status group by status order by total desc"),

    DELIVERY_OUTCOMES("delivery-outcomes",
            "Notification deliveries grouped by channel and status",
            "select channel, status from notification_delivery",
            "mart_delivery_outcomes",
            "select channel, status, count(*) as total from mart_delivery_outcomes "
                    + "group by channel, status order by channel, status");

    private final String code;
    private final String description;
    private final String sourceSql;
    private final String martTable;
    private final String martQuery;

    AnalyticsReport(String code, String description, String sourceSql, String martTable, String martQuery) {
        this.code = code;
        this.description = description;
        this.sourceSql = sourceSql;
        this.martTable = martTable;
        this.martQuery = martQuery;
    }

    static Optional<AnalyticsReport> fromCode(String code) {
        return Arrays.stream(values()).filter(report -> report.code.equals(code)).findFirst();
    }

    String code() {
        return code;
    }

    String description() {
        return description;
    }

    String sourceSql() {
        return sourceSql;
    }

    String martTable() {
        return martTable;
    }

    String martQuery() {
        return martQuery;
    }
}
