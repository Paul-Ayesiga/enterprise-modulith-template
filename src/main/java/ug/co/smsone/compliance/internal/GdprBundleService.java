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
import ug.co.smsone.shared.persistence.MappedTables;

/**
 * The <b>redacted disclosure bundle</b>: a bounded JSON view of one organization's records, safe to
 * hand to a data subject, a regulator, or a departing tenant's lawyer. It is one of the two halves the
 * old {@code OrgExportService} tried to be at once, and the other half is
 * {@link TenantExtractionService}.
 *
 * <p><b>Why they can never be the same call, stated once and kept here.</b> {@link #EXCLUDED_COLUMNS}
 * is the whole argument. {@code webhook_subscription.secret} is a live signing credential and
 * {@code api_key.secret_hash} is a live authentication credential; a disclosure bundle that shipped
 * either would hand a third party the means to impersonate the tenant's systems, and no redaction
 * added later can un-send it. An <em>extraction</em> has the opposite obligation: it is the tenant
 * receiving its own database, and a copy with the secrets stripped out is a copy that does not boot —
 * every webhook signature fails and every key is dead. One artifact must omit exactly what the other
 * must include. That is not a flag on a method; it is two different products with two different
 * recipients, and merging them is how a secret escapes. Anything that widens this bundle's reach
 * belongs in {@link TenantExtractionService} instead.
 *
 * <p>The second difference is completeness. This bundle is <b>capped and curated</b>: it answers "what
 * of this organization may this recipient see", so a hand-listed set of tables is the right shape —
 * every entry is a deliberate disclosure decision someone can be asked to defend. The extraction
 * answers "everything the tenant owns", where a hand-listed set is a latent defect, and it is derived
 * from the catalog for exactly that reason. The two lists are allowed to differ, and they do: this one
 * omits ten tenant-tier tables on purpose (see {@link TenantExtractionService}), which is a bug in an
 * extraction and a decision in a disclosure.
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
 * in a disclosure bundle returns data the tenant asked to be rid of. Saying that out loud in SQL is
 * also what makes the extract indexable: every org-scoped index on the soft-deletable tables is
 * PARTIAL on {@code deleted_at is null}, and Postgres uses a partial index only when the query PROVES
 * its predicate — which it cannot if the query never mentions it. Measured on 200k rows: {@code
 * document} went from a parallel seq scan (3964 buffers, 11.0 ms) to an index scan on
 * {@code idx_document_org_recent} (5 buffers, 1.5 ms) that also returns the rows ALREADY in the
 * export's order, no Sort node; {@code ticket} went 4251 buffers / 25.8 ms → 1.0 ms. So no new index
 * is needed here — the predicate unlocks the ones V23/V36 already built. (The extraction takes the
 * deleted rows too, and that difference is deliberate: a restore must be faithful, a disclosure must
 * not resurrect.)
 *
 * <p>{@code organization} is the ONE deliberate exception, and it is the trap a uniform predicate
 * walks straight into: tenant deletion is SOFT and stamps only the organization row (memberships and
 * roles stay live underneath it, so an un-delete restores a working org — see
 * {@code OrganizationService#delete}), and offboarding is what you do AFTER deleting a tenant. Filter
 * that row the same way and the bundle omits the very organization it is named for.
 *
 * <p><b>Both tiers, one connection (ADR 0010 §2).</b> This method reads platform-tier tables
 * ({@code organization}, {@code external_organization}, {@code api_key}, {@code api_usage_daily}) and
 * tenant-tier ones ({@code membership}, {@code ticket}, {@code webhook_subscription}, …) in the same
 * pass. It therefore runs pinned to the TENANT axis — the caller is a platform operator whose thread
 * is on {@code platform}, so {@code AdminComplianceController} enters the tenant explicitly with
 * {@code TenantContext.callAs(orgId, …)} (ADR 0010 §5.15) — and every platform-tier table is named
 * {@code platform.<table>}, which resolves regardless of {@code search_path}. The four split tables it
 * reads ({@code document}, {@code integration}, {@code exchange_job}, {@code audit_log}) stay
 * UNQUALIFIED on purpose: an org-scoped row of a split table lives in the tenant's copy, and
 * unqualified is how you say "the tenant's". Which name a table gets is not decided here — it is read
 * off the entity mapping through {@link MappedTables}, so this class cannot hold an opinion about
 * tiers that has drifted from the one {@code docs/DATA_MODEL.md} records.
 */
@Service
class GdprBundleService {

    private static final Logger log = LoggerFactory.getLogger(GdprBundleService.class);

    /**
     * Columns that must never leave the database, even encrypted — and the reason this class exists
     * separately from {@link TenantExtractionService}. See the class note.
     */
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
     * The two tables in this bundle that no entity maps, so {@link MappedTables} structurally cannot
     * answer for them — and the one place their home is therefore written by hand.
     *
     * <p>Everything else resolves through the mapping instead of through a list here, and that is the
     * point: the tier assignment already lives in {@code docs/DATA_MODEL.md}, is enforced onto the
     * entities by {@code PlatformSchemaQualificationTest}, and a second copy of it in this class would
     * drift — silently, because addressing a platform table bare from a tenant axis produces a
     * statement that runs, matches nothing, and reports success. So the four SPLIT tables this bundle
     * reads ({@code audit_log}, {@code document}, {@code integration}, and {@code exchange_job} below)
     * come out bare, which is exactly right: an org-scoped row of a split table is in the tenant's
     * copy, and bare is how you say "the tenant's".
     */
    private static final Map<String, String> UNMAPPED_TABLES = Map.of(
            // Platform-tier: the export job's cross-tenant `order by day` is load-bearing fairness
            // (ADR 0010 §2), and no entity maps it — UsageExportJob is JdbcTemplate throughout.
            "api_usage_daily", "platform.api_usage_daily",
            // Split, and bare for the reason above: this bundle only ever asks for a named org's jobs,
            // which are the tenant's. ExchangeJobStore is JdbcTemplate too, hence no mapping to ask.
            "exchange_job", "exchange_job");

    /**
     * One table's extract: where the org id lives, the order that makes the result reproducible, and
     * which side of {@code deleted_at} it takes. Every field is a compile-time constant and none is
     * ever built from input — that is the whole reason this class is allowed to concatenate SQL. The
     * SCHEMA is deliberately not a field: see {@link #UNMAPPED_TABLES}.
     */
    private record Extract(String table, String orgColumn, String order, Rows rows) {

        String sql(String from, int limit) {
            return "select * from " + from
                    + " where " + orgColumn + " = ?"
                    + (rows == Rows.LIVE_ONLY ? " and deleted_at is null" : "")
                    + " order by " + order
                    + " limit " + limit;
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;

    /**
     * Bare name → the name SQL must use, resolved once at startup. Resolved eagerly on purpose: a
     * table this bundle names that neither the mapping nor {@link #UNMAPPED_TABLES} can place makes
     * the application fail to start, with the table named. Resolved lazily it would instead be a 500
     * on somebody's disclosure request, months later, in the middle of an offboarding.
     */
    private final Map<String, String> from;

    GdprBundleService(JdbcTemplate jdbc, AuditLog auditLog, MappedTables mappedTables) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Extract extract : EXTRACTS) {
            String unmapped = UNMAPPED_TABLES.get(extract.table());
            resolved.put(extract.table(),
                    unmapped != null ? unmapped : mappedTables.qualified(extract.table()));
        }
        this.from = Map.copyOf(resolved);
    }

    /**
     * @implNote {@code @Transactional}, so the caller's {@code TenantContext.callAs(orgId, …)} MUST
     *     wrap this call rather than sit inside it: the schema is chosen when the connection is
     *     borrowed and this transaction binds one (ADR 0010 §3.2, and {@code TenantContext.set} throws
     *     if you get it the wrong way round).
     * @implNote <b>Read-WRITE, and it has to be.</b> Twenty-three of the twenty-four statements below
     *     are selects; the last one is not. {@code auditLog.record} writes the disclosure row, and
     *     since {@code AuditLogImpl} became a {@code JdbcTemplate} insert — the split-table routing
     *     needs to NAME the schema, which no single JPA mapping can (ADR 0010 §2) — that insert hits
     *     the connection immediately. Declared {@code readOnly = true}, Spring sets the connection
     *     read-only, pgjdbc opens {@code BEGIN READ ONLY}, and Postgres refuses the insert outright:
     *     every disclosure request answers 500.
     *     <p>It was {@code readOnly = true}, and that was already wrong before it was loud. The audit
     *     row used to go through {@code AuditEntryRepository.save}, and a read-only transaction puts
     *     Hibernate in {@code FlushMode.MANUAL} — so the persist was scheduled, never flushed, and
     *     silently discarded at commit. This method has never recorded a disclosure. The flag was
     *     describing what the method mostly does rather than what it does, and the one statement it
     *     was wrong about is the one that makes the export auditable at all.
     *     <p>Keeping the audit row INSIDE the same transaction is deliberate: the bundle and the record
     *     that it was produced now commit together, so there is no window in which a tenant's data was
     *     disclosed with nothing to say so.
     */
    @Transactional
    Map<String, Object> export(UUID orgId) {
        Map<String, Object> tables = new LinkedHashMap<>();
        List<String> truncated = new ArrayList<>();
        for (Extract extract : EXTRACTS) {
            // ROW_CAP + 1, and the extra row is never emitted: it is the only way to tell "this table
            // has exactly the cap" from "the cap, and more behind it" without a COUNT(*) — which ADR
            // 0002 forbids and which would double the cost of the export for a number nobody reads.
            List<Map<String, Object>> rows =
                    jdbc.queryForList(extract.sql(from.get(extract.table()), ROW_CAP + 1), orgId);
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
        // The one sentence that stops this artifact being mistaken for the other one. A recipient who
        // reads nothing else must not walk away believing they were handed the tenant's database:
        // secrets are gone, ten tenant-tier tables are not here, and the row cap applies.
        bundle.put("bundleKind", "REDACTED_DISCLOSURE — not a tenant extraction. Secret-bearing "
                + "columns are removed, per-table row caps apply, and only the tables listed here are "
                + "disclosed. Lifting this organization to another deployment uses the extraction "
                + "path, which is a different artifact with different contents.");
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
            log.warn("Disclosure bundle for {} hit the {}-row cap on {} — the bundle is INCOMPLETE",
                    orgId, ROW_CAP, truncated);
        }
        // Unchanged action name. The audit trail is queried by it and 'org_exported' is what the rows
        // already say; renaming it would split one history into two for no gain. The org is non-null,
        // so under ADR 0010 §2 this row lands in the TENANT's audit_log — the tenant's own record of
        // having been disclosed, which is exactly where a disclosure belongs.
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
     *
     * <p><b>This list is a disclosure decision, not an inventory.</b> It is deliberately narrower than
     * the tenant schema: {@code geo_stamp}, {@code geo_capture_policy}, {@code user_device_trust},
     * {@code feature_flag_org_override}, {@code org_group_member}, {@code role_permission},
     * {@code ticket_message}, {@code exchange_job_error}, {@code integration_setting} and
     * {@code maintenance_window} are all the tenant's and none of them is here. In the extraction that
     * would be a defect and {@link TenantExtractionService} makes it impossible; here it is a choice.
     * Adding a table is a disclosure decision — make it deliberately, and say why in a comment.
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
        // integration is one of the seven SPLIT tables: NULL org_id is the platform default and
        // non-NULL is this org's own override (V33:15). The predicate names an org, so it can only ever
        // match the tenant's copy — which is what the bare name the mapping hands back resolves to.
        extracts.add(new Extract("integration", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("webhook_subscription", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        // Delivery/payment/job rows are append-only logs — no deleted_at; the retention purge removes
        // them outright rather than stamping them.
        extracts.add(new Extract("webhook_delivery", "org_id", NEWEST_FIRST, Rows.NO_SOFT_DELETE));
        // api_key is PLATFORM whole (ADR §2.1): findByPrefix runs against a global uniqueness index
        // before any tenant is known. Its secret_hash is redacted here and, in an extraction, the keys
        // are re-minted rather than copied — two different answers to the same column.
        extracts.add(new Extract("api_key", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("billing_account", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("payment", "org_id", NEWEST_FIRST, Rows.NO_SOFT_DELETE));
        // api_usage_daily is the one table here with no id column at all: its key is (org_id, day), so
        // `day desc` is both the reproducible order and a backward scan of the primary key.
        extracts.add(new Extract("api_usage_daily", "org_id", "day desc", Rows.NO_SOFT_DELETE));
        extracts.add(new Extract("exchange_job", "org_id", NEWEST_FIRST, Rows.NO_SOFT_DELETE));
        extracts.add(new Extract("exchange_schedule", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        // document is split too, and its NULL org_id means PERSONAL, not unknown-tenant: a personal
        // document belongs to a human and must never travel with the org (DATA_MODEL.md:219). The
        // org_id predicate is what keeps it out.
        extracts.add(new Extract("document", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        extracts.add(new Extract("ticket", "org_id", NEWEST_FIRST, Rows.LIVE_ONLY));
        // audit_log's clock is occurred_at, not created_at — and that is the ordering its org index is
        // built on (idx_audit_org_occurred, V13). Ordering it by created_at would be both the wrong
        // timeline and an unnecessary sort of every audit row the tenant ever produced. Split table:
        // the org's rows are the tenant's compliance record and live in the tenant's copy.
        extracts.add(new Extract("audit_log", "org_id", "occurred_at desc, id desc", Rows.NO_SOFT_DELETE));
        return List.copyOf(extracts);
    }
}
