package ug.co.smsone.analytics.internal;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fixed catalog of curated reports. Each carries **developer-authored** SQL only (never end-user
 * input, per the {@code AnalyticsEngine} contract): a Postgres {@code sourceSql} materialized into the
 * DuckDB mart {@code martTable}, then an aggregate {@code martQuery} run against that mart.
 *
 * <p><b>{@code sourceSql} is row-level on purpose, and it is not free.</b> Every column it selects is
 * copied out of Postgres and into DuckDB on each refresh — {@code delivery-outcomes} moves 300,000
 * rows to answer with four. Select the narrowest set of columns the {@code martQuery} actually groups
 * by; pre-aggregating in {@code sourceSql} instead would be faster still and is deliberately not done,
 * because then the mart is the answer and the OLAP engine is doing nothing. What keeps the cost
 * bounded is {@code AnalyticsReportService}: a refresh happens at most once per
 * {@code app.analytics.mart-ttl}, so a report can be up to that interval behind its source.
 *
 * <p><b>{@code sourceSql} runs as raw JDBC, so {@code @SQLRestriction} does not apply.</b> A report
 * over a soft-deletable table must filter {@code deleted_at is null} itself, or it silently disagrees
 * with the admin API reading the same table — and the report is the one used for headcounts and
 * licence reconciliation. The soft-deletable tables are {@code setting}, {@code feature_flag},
 * {@code person}, {@code external_identity}, {@code person_contact}, {@code organization},
 * {@code external_organization}, {@code org_role}, {@code membership},
 * {@code webhook_subscription}, {@code translation}, {@code document}, {@code exchange_schedule},
 * {@code org_subscription}, {@code billing_account}, {@code person_profile}, {@code api_key}, {@code org_group}, {@code user_device}, {@code org_security_policy}, {@code integration}, {@code maintenance_window} and {@code ticket}; {@code notification_delivery} is not one of them.
 *
 * <p><b>PHASE 2 (ADR 0010): every {@code sourceSql} here reads a PLATFORM table while running on the
 * CALLER's axis.</b> {@code person} and {@code notification_delivery} are both platform-tier (§2), but
 * the refresh borrows its connection on whatever axis the request pinned — a tenant's, for an org
 * admin asking for a report. Today that is harmless because every axis resolves to the same schema;
 * once {@code platform} and {@code tenant_pool} are separate, an unqualified {@code from person} on a
 * tenant path resolves to nothing. These two statements are the ones to schema-qualify (or to run
 * inside a {@code TenantContext.runAsPlatform(…)} in {@code AnalyticsReportService}) when the tables
 * move; a report over a tenant-tier table would instead need a refresh per tenant.
 */
enum AnalyticsReport {

    // person, not app_user: the identity is the person row now, and the status column moved with it.
    // A headcount over the old table does not fail loudly — the whole report just stops existing — so
    // the source table is the one thing here worth checking against a migration.
    PEOPLE_BY_STATUS("users-by-status",
            "Provisioned people grouped by lifecycle status",
            "select status from person where deleted_at is null",
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
