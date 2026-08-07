package ug.co.smsone.compliance.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;

/**
 * Tenant offboarding's other half: a full JSON bundle of one organization's records, read straight
 * across the org-owned tables — the same cross-cutting data-lifecycle reach the purge and erasure
 * paths already have (AGENTS §7). Table names and their org columns are CONSTANTS, never input;
 * secret-bearing columns are dropped at the source so no call path can leak them. Row caps keep the
 * bundle a handover document, not a database dump — the caps are stated in the payload.
 */
@Service
class OrgExportService {

    /** table → the column carrying the org id. Constants — never input. */
    private static final Map<String, String> ORG_TABLES = buildTables();
    /** Columns that must never leave the database, even encrypted. */
    private static final Map<String, Set<String>> EXCLUDED_COLUMNS = Map.of(
            "webhook_subscription", Set.of("secret"),
            "api_key", Set.of("secret_hash"));
    private static final int ROW_CAP = 1000;

    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;

    OrgExportService(JdbcTemplate jdbc, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    Map<String, Object> export(UUID orgId) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("organizationId", orgId.toString());
        bundle.put("rowCapPerTable", ROW_CAP);
        Map<String, Object> tables = new LinkedHashMap<>();
        ORG_TABLES.forEach((table, orgColumn) -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select * from " + table + " where " + orgColumn + " = ? limit " + ROW_CAP, orgId);
            Set<String> excluded = EXCLUDED_COLUMNS.getOrDefault(table, Set.of());
            rows.forEach(row -> excluded.forEach(row::remove));
            if (!rows.isEmpty()) {
                tables.put(table, rows);
            }
        });
        bundle.put("tables", tables);
        auditLog.record("compliance.org_exported", orgId, orgId.toString(), null,
                "tables=" + tables.size());
        return bundle;
    }

    private static Map<String, String> buildTables() {
        Map<String, String> tables = new LinkedHashMap<>();
        // The tenant row itself, keyed by its own id — organization.id IS the tenant key (V11), and the
        // org column every table below carries holds that same value.
        tables.put("organization", "id");
        // The identifiers other systems know this tenant by. They used to ride along inside the
        // organization row as kc_org_id; that column became a table (V11), so the bundle follows it
        // rather than quietly shipping one identifier fewer than it did before.
        tables.put("external_organization", "organization_id");
        // Child tables without an org column (org_group_member, integration_setting) are reachable
        // through their exported parents; settings VALUES are secret-bearing and stay out regardless.
        for (String table : List.of("membership", "org_role", "org_group",
                "org_subscription", "org_security_policy", "org_sla_override", "org_retention_override",
                "integration", "webhook_subscription", "webhook_delivery",
                "api_key", "billing_account", "payment", "api_usage_daily", "exchange_job",
                "exchange_schedule", "document", "ticket", "audit_log")) {
            tables.put(table, "org_id");
        }
        return tables;
    }
}
