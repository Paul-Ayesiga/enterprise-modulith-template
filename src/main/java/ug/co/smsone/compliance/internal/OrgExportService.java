package ug.co.smsone.compliance.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;

/**
 * Tenant offboarding's other half: a full JSON bundle of one organization's records, read straight
 * across the org-owned tables — the same cross-cutting data-lifecycle reach the purge and erasure
 * paths already have (AGENTS §7). Table names, their org columns and their sort orders are CONSTANTS,
 * never input; secret-bearing columns are dropped at the source so no call path can leak them.
 *
 * <p><b>The bundle is a legal artifact, so it has to be reproducible.</b> Every extract carries an
 * explicit ORDER BY. Without one, a {@code limit} keeps whatever rows the executor happened to hand
 * back first — for these tables a PARALLEL seq scan, so the answer depends on worker count, on where
 * the rows physically sit in the heap, and on whatever the planner decides today. Two exports of the
 * same unchanged data could disagree about which rows the tenant was given, and neither could be
 * reproduced afterwards to prove what was handed over. That was filed as a performance nit. It is a
 * correctness defect in a compliance artifact, and the ORDER BY is the fix.
 *
 * <p><b>Live rows only — and the bundle says so.</b> A soft-deleted row is one the tenant deleted: it
 * is invisible on every product surface and the retention purge will hard-delete it. Handing it back
 * in an offboarding bundle returns data the tenant asked to be rid of. Saying that out loud in SQL is
 * also what makes the extract indexable: every org-scoped index on the soft-deletable tables is
 * PARTIAL on {@code deleted_at is null}, and Postgres uses a partial index only when the query PROVES
 * its predicate — which it cannot if the query never mentions it. Measured on 200k rows: {@code
 * document} went from a parallel seq scan (3964 buffers, 11.0 ms) to an index scan on
 * {@code idx_document_org_recent} (5 buffers, 1.5 ms) that also returns the rows ALREADY in the
 * export's order, no Sort node; {@code ticket} went 4251 buffers / 25.8 ms → 1.0 ms. So no new index
 * is needed here — the predicate unlocks the ones V23/V36 already built.
 *
 * <p>{@code organization} is the ONE deliberate exception, and it is the trap a uniform predicate
 * walks straight into: tenant deletion is SOFT and stamps only the organization row (memberships and
 * roles stay live underneath it, so an un-delete restores a working org — see
 * {@code OrganizationService#delete}), and offboarding is what you do AFTER deleting a tenant. Filter
 * that row the same way and the bundle omits the very organization it is named for.
 */
@Service
class OrgExportService {

    private static final Logger log = LoggerFactory.getLogger(OrgExportService.class);

    /** Columns that must never leave the database, even encrypted. */
    private static final Map<String, Set<String>> EXCLUDED_COLUMNS = Map.of(
            "webhook_subscription", Set.of("secret"),
            "api_key", Set.of("secret_hash"));

    /**
     * A bundle is one JSON document, so it has to be bounded (ADR 0002 rules out both an unbounded
     * response and the COUNT that would size one). What does NOT belong in a legal export is a cap
     * that applies itself in silence: a bundle that omits rows without saying so is worse than one
     * that refuses, because the recipient cannot tell the difference between "this tenant had 1,000
     * documents" and "this tenant had 90,000 and you were given a slice". So the cap stays and is made
     * loud — see {@link #export}: a truncated bundle reports {@code complete: false} and names every
     * table it cut, the audit row records it, and the log warns. Ordering is newest-first, which makes
     * a truncated table "the most recent 1,000 rows", a sentence an operator can defend in writing,
     * rather than an arbitrary sample nobody can reconstruct.
     */
    private static final int ROW_CAP = 1000;

    /** The house sort (AGENTS §3.3); {@code id} is the tiebreaker that makes it unique. */
    private static final String NEWEST_FIRST = "created_at desc, id desc";

    private static final List<Extract> EXTRACTS = buildExtracts();

    /**
     * What an extract does about {@code deleted_at}. Three named cases rather than a boolean, because
     * "the table cannot be soft-deleted" and "the table can be, and we deliberately take the deleted
     * rows too" are different answers that a reader must be able to tell apart at the call site.
     */
    private enum Rows {
        /** No {@code deleted_at} column at all — every row is live by construction. */
        NO_SOFT_DELETE,
        /** Soft-deletable; the export takes the live side, and the SQL says so. */
        LIVE_ONLY,
        /** Soft-deletable and taken whole, deliberately — see the class note on {@code organization}. */
        LIVE_AND_DELETED
    }

    /**
     * One table's extract: where the org id lives, the order that makes the result reproducible, and
     * which side of {@code deleted_at} it takes. Every field is a compile-time constant and none is
     * ever built from input — that is the whole reason this class is allowed to concatenate SQL.
     */
    private record Extract(String table, String orgColumn, String order, Rows rows) {

        String sql(int limit) {
            return "select * from " + table
                    + " where " + orgColumn + " = ?"
                    + (rows == Rows.LIVE_ONLY ? " and deleted_at is null" : "")
                    + " order by " + order
                    + " limit " + limit;
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;

    OrgExportService(JdbcTemplate jdbc, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    Map<String, Object> export(UUID orgId) {
        Map<String, Object> tables = new LinkedHashMap<>();
        List<String> truncated = new ArrayList<>();
        for (Extract extract : EXTRACTS) {
            // ROW_CAP + 1, and the extra row is never emitted: it is the only way to tell "this table
            // has exactly the cap" from "the cap, and more behind it" without a COUNT(*) — which ADR
            // 0002 forbids and which would double the cost of the export for a number nobody reads.
            List<Map<String, Object>> rows = jdbc.queryForList(extract.sql(ROW_CAP + 1), orgId);
            if (rows.size() > ROW_CAP) {
                truncated.add(extract.table());
                rows = new ArrayList<>(rows.subList(0, ROW_CAP));
            }
            Set<String> excluded = EXCLUDED_COLUMNS.getOrDefault(extract.table(), Set.of());
            rows.forEach(row -> excluded.forEach(row::remove));
            if (!rows.isEmpty()) {
                tables.put(extract.table(), rows);
            }
        }

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("organizationId", orgId.toString());
        // `complete` is the one field a program must branch on, so it goes first and is a plain
        // boolean: a consumer that checks nothing else still cannot mistake a cut bundle for a whole
        // one. The prose beside it is for the human the bundle is actually handed to.
        bundle.put("complete", truncated.isEmpty());
        bundle.put("rowCapPerTable", ROW_CAP);
        bundle.put("truncatedTables", List.copyOf(truncated));
        bundle.put("rowOrder", "Newest first and fixed per table (created_at desc, id desc; audit_log "
                + "by occurred_at, api_usage_daily by day), so the same data always yields the same "
                + "bundle and a truncated table holds the most recent rows.");
        bundle.put("rowScope", "Live rows only: soft-deleted rows are excluded. The organization row "
                + "itself is the exception and is included even once deleted, because offboarding runs "
                + "after the tenant is deleted.");
        bundle.put("tables", tables);

        if (!truncated.isEmpty()) {
            // Loud on every channel the recipient might be reading: the payload, the audit trail, and
            // the operator's log. A silent cap is the defect; a stated one is a documented boundary.
            log.warn("Org export for {} hit the {}-row cap on {} — the bundle is INCOMPLETE",
                    orgId, ROW_CAP, truncated);
        }
        auditLog.record("compliance.org_exported", orgId, orgId.toString(), null,
                "tables=" + tables.size()
                        + (truncated.isEmpty() ? " complete=true"
                                : " complete=false truncated=" + String.join(",", truncated)));
        return bundle;
    }

    /**
     * The extract list, in the order the bundle presents it. Spelled out one line per table rather
     * than folded into loops: the {@link Rows} answer has to be checkable against the schema table by
     * table, and a reviewer who cannot see which tables carry {@code deleted_at} cannot verify the
     * export's scope at all.
     */
    private static List<Extract> buildExtracts() {
        List<Extract> extracts = new ArrayList<>();
        // The tenant row itself, keyed by its own id — organization.id IS the tenant key (V11), and the
        // org column every table below carries holds that same value. LIVE_AND_DELETED: see the class
        // note — this row is normally already soft-deleted by the time anyone exports the tenant.
        extracts.add(new Extract("organization", "id", NEWEST_FIRST, Rows.LIVE_AND_DELETED));
        // The identifiers other systems know this tenant by. They used to ride along inside the
        // organization row as kc_org_id; that column became a table (V11), so the bundle follows it
        // rather than quietly shipping one identifier fewer than it did before. Deleting the org does
        // NOT delete this link (OrganizationDeleted says why), so LIVE_ONLY still returns it.
        extracts.add(new Extract("external_organization", "organization_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        // Child tables without an org column (org_group_member, integration_setting) are reachable
        // through their exported parents; settings VALUES are secret-bearing and stay out regardless.
        extracts.add(new Extract("membership", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("org_role", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("org_group", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("org_subscription", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("org_security_policy", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        // The two per-org overrides are plain rows with no lifecycle of their own: V38/V39 gave them
        // no deleted_at, so there is no live/deleted question to answer for them.
        extracts.add(new Extract("org_sla_override", "org_id", NEWEST_FIRST, Rows.NO_SOFT_DELETE));
        extracts.add(new Extract("org_retention_override", "org_id", NEWEST_FIRST, Rows.NO_SOFT_DELETE));
        extracts.add(new Extract("integration", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("webhook_subscription", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        // Delivery/payment/job rows are append-only logs — no deleted_at; the retention purge removes
        // them outright rather than stamping them.
        extracts.add(new Extract("webhook_delivery", "org_id", NEWEST_FIRST, Rows.NO_SOFT_DELETE));
        extracts.add(new Extract("api_key", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("billing_account", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("payment", "org_id", NEWEST_FIRST, Rows.NO_SOFT_DELETE));
        // api_usage_daily is the one table here with no id column at all: its key is (org_id, day), so
        // `day desc` is both the reproducible order and a backward scan of the primary key.
        extracts.add(new Extract("api_usage_daily", "org_id", "day desc", Rows.NO_SOFT_DELETE));
        extracts.add(new Extract("exchange_job", "org_id", NEWEST_FIRST, Rows.NO_SOFT_DELETE));
        extracts.add(new Extract("exchange_schedule", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("document", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("ticket", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        // audit_log's clock is occurred_at, not created_at — and that is the ordering its org index is
        // built on (idx_audit_org_occurred, V13). Ordering it by created_at would be both the wrong
        // timeline and an unnecessary sort of every audit row the tenant ever produced.
        extracts.add(new Extract("audit_log", "org_id", "occurred_at desc, id desc", Rows.NO_SOFT_DELETE));
        return List.copyOf(extracts);
    }
}
