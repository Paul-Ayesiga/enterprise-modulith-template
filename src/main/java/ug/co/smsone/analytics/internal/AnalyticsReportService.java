package ug.co.smsone.analytics.internal;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import ug.co.smsone.analytics.AnalyticsEngine;

/**
 * Runs a catalog report: refresh its mart from Postgres, then return the aggregate rows. Refreshing
 * on each call keeps the template simple and always-fresh; a high-traffic report would instead be
 * materialized on a schedule and only queried here.
 */
@Service
class AnalyticsReportService {

    private final AnalyticsEngine engine;

    AnalyticsReportService(AnalyticsEngine engine) {
        this.engine = engine;
    }

    List<Map<String, Object>> run(AnalyticsReport report) {
        engine.materializeFromPostgres(report.sourceSql(), report.martTable());
        return engine.query(report.martQuery());
    }
}
