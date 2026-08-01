package ug.co.smsone.analytics.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * Curated analytics reports (embedded DuckDB OLAP). The catalog is fixed and its SQL developer-authored;
 * clients pick a report by code, never supply SQL. Reports aggregate across the whole system, so this is
 * a platform surface — read-only, hence {@code platform-support} is the floor (§5.1: a check names the
 * minimum tier).
 */
@RestController
@RequestMapping("/api/v1/analytics/reports")
class AnalyticsReportController {

    private static final String RESOURCE_TYPE = "analytics-report";

    private final AnalyticsReportService reports;

    AnalyticsReportController(AnalyticsReportService reports) {
        this.reports = reports;
    }

    record ReportSummary(String code, String description) {
    }

    record ReportResult(String code, String description, List<Map<String, Object>> rows) {
    }

    @GetMapping
    @Operation(summary = "List the available analytics reports")
    @PreAuthorize("hasRole('platform-support')")
    // A bare List, deliberately: the catalog is the AnalyticsReport enum — bounded by code, not by
    // data — so the cursor rule for growable collections (§3.3) does not apply.
    List<ResourceObject> catalog() {
        return Arrays.stream(AnalyticsReport.values())
                .map(report -> new ResourceObject(report.code(), RESOURCE_TYPE,
                        new ReportSummary(report.code(), report.description())))
                .toList();
    }

    @GetMapping("/{code}")
    @Operation(summary = "Run an analytics report",
            description = """
                    The report's mart is refreshed from Postgres on every call before it is queried, \
                    so rows are always current and the response time scales with the source data.""")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject run(@PathVariable String code) {
        AnalyticsReport report = AnalyticsReport.fromCode(code)
                .orElseThrow(() -> new NotFoundException("Unknown report '" + code + "'."));
        return new ResourceObject(report.code(), RESOURCE_TYPE,
                new ReportResult(report.code(), report.description(), reports.run(report)));
    }
}
