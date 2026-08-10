package ug.co.smsone.compliance.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * <strong>Every table in the {@code platform} schema, and what an extraction does with it.</strong> The
 * platform-side half of ADR 0010 §6's bundle — the half {@code pg_dump -n t_&lt;hex&gt;} structurally
 * cannot produce, because none of these tables is in the tenant's schema.
 *
 * <h2>Derived from the catalogue, judged in code, and it refuses rather than guesses</h2>
 *
 * <p>The table SET comes from {@code information_schema}; the DISPOSITION comes from this file. That
 * split is the same one {@code TenantTierTables} makes and it is made here for a sharper reason: on the
 * tenant side, forgetting a table under-copies and the tenant notices. On the platform side, forgetting
 * a table is invisible in both directions — nobody misses {@code queue_signal} until two deployments
 * lease the same tenant, and nobody misses {@code plan} until every quota is unlimited and every
 * feature 403s at the same time (§6 item 4). So a platform table with no recorded judgement
 * <b>fails the bundle at plan time, naming itself</b>, and the only way to make the failure go away is
 * to decide what happens to it.
 *
 * <p><b>That refusal has already earned its place, and the evidence is in this file.</b> §6 names four
 * tables as "fresh and empty, never copied": {@code event_publication}, {@code event_inbox},
 * {@code idempotency_key}, {@code shedlock}. The catalogue now holds <b>seven</b> — Phase 3 added
 * {@code platform.queue_signal} (V56), Phase 5 added {@code platform.tenant_freeze} (V58) and Phase 7
 * added {@code platform.tenant_cutover} (V60), each after §6 was written, and each carrying exactly
 * the hazard §6 describes: a copied {@code queue_signal} row hands the new deployment a lease token
 * the platform still believes it holds, a copied {@code tenant_freeze} row pauses every queue in a
 * deployment where nothing is being promoted, and a copied {@code tenant_cutover} row claims
 * replication objects in a database the new deployment cannot even reach. A hand
 * list would have shipped four. The catalogue asked, and the answer had to be written down.
 *
 * <h2>The reasons are data, not comments</h2>
 *
 * <p>Every entry carries the sentence that justifies it, and the manifest prints those sentences. An
 * extraction's most expensive failure mode is not a missing row — it is an operator who did not know
 * that the person graph, the usage ledger and the platform's integration defaults were staying behind
 * until something on the far side went quiet. {@link #whatDoesNotTravel()} is that list, and it exists
 * so nobody has to reconstruct it from a diff.
 */
@Component
class TenantBundlePlan {

    /** Flyway's own bookkeeping: it describes the schema, not the installation. Never a bundle part. */
    private static final String SCHEMA_HISTORY = "flyway_schema_history";

    /** The tier's own schema. Named, never resolved through {@code search_path} (AGENTS §1). */
    static final String PLATFORM_SCHEMA = "platform";

    /**
     * One platform table's fate.
     *
     * @param orgPredicate the {@code where} that selects this organization's rows, or {@code null} when
     *     the whole table travels ({@link BundleDisposition#SNAPSHOT_WHOLE_TABLE}) or none of it does.
     *     Exactly one {@code ?} when present, so every read binds one argument.
     * @param why the sentence the manifest prints. Written for the operator reading it at 03:00, not
     *     for the reviewer reading it now.
     */
    record PlatformTable(String table, BundleDisposition disposition, String orgPredicate, String why) {

        String read() {
            if (!disposition.carriesRows()) {
                throw new IllegalStateException("platform." + table + " is " + disposition
                        + " and a bundle carries no rows of it. There is deliberately no SQL here to"
                        + " ask for them — ADR 0010 §6 item 6 is a property of this class, not a rule"
                        + " somebody has to remember. If this table now has to travel, change its"
                        + " disposition and say why.");
            }
            return "select * from " + PLATFORM_SCHEMA + "." + table
                    + (orgPredicate == null ? "" : " where " + orgPredicate);
        }
    }

    /**
     * The judgement, one line per platform table. Ordered so a reader meets §6's eleven items in the
     * document's order and the "stays" list last.
     *
     * <p>It is a {@code Map} keyed by table so {@link #plan()} can join it against the catalogue in
     * both directions: a catalogue table missing from here fails, and an entry here naming a table the
     * catalogue does not have fails too. The second half matters as much as the first — an entry left
     * behind by a dropped table is a judgement about nothing, and it would quietly absorb the day
     * somebody re-created a table of that name for a different purpose.
     */
    private static final Map<String, PlatformTable> DISPOSITIONS = dispositions();

    private final JdbcTemplate jdbc;

    TenantBundlePlan(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The platform half of the bundle, checked against the live catalogue and <b>ordered so a restore
     * can replay it</b>: a table appears only after every table it references.
     *
     * <p>Runs on the PLATFORM axis: every name here is schema-qualified, so it does not depend on
     * {@code search_path}, but the connection still has to be one the caller is allowed to read
     * {@code platform} on.
     *
     * <p><b>The order is not alphabetical, and the difference is a restore that fails.</b> Alphabetical
     * puts {@code external_organization} before {@code organization}, and
     * {@code external_organization_organization_id_fkey} is a REAL foreign key — the last one pointing
     * at {@code organization} and the only referrer platform-tier enough to keep it (see
     * {@code docs/DATA_MODEL.md}). A bundle emitted alphabetically therefore aborts on its second
     * platform statement, every time, and the tenant tables that were already in the file roll back with
     * it. That the sibling pair {@code plan} / {@code plan_entitlement} happens to sort correctly is
     * luck, not a reason to rely on the alphabet.
     *
     * @throws IllegalStateException if the catalogue and the judgement disagree in either direction
     */
    List<PlatformTable> plan() {
        List<String> catalogue = jdbc.queryForList("""
                select c.relname from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = ? and c.relkind = 'r' and c.relname <> ?
                 order by c.relname
                """, String.class, PLATFORM_SCHEMA, SCHEMA_HISTORY);
        if (catalogue.isEmpty()) {
            throw new IllegalStateException("the '" + PLATFORM_SCHEMA + "' schema holds no tables, so"
                    + " this connection is not looking at a migrated installation at all (ADR 0010 §4.2)");
        }
        List<String> unjudged = catalogue.stream().filter(table -> !DISPOSITIONS.containsKey(table))
                .toList();
        if (!unjudged.isEmpty()) {
            throw new IllegalStateException("platform-tier table(s) " + unjudged + " have no extraction"
                    + " disposition, so a bundle would silently leave them out of the answer to 'what"
                    + " does this tenant get, and what stays'. Neither outcome is safe by default: a"
                    + " catalogue table that should have been snapshotted fails on the far side with no"
                    + " error at all (ADR 0010 §6 item 4), and one that should have stayed leaks the"
                    + " platform's rows into a tenant's deployment. Add it to TenantBundlePlan with the"
                    + " sentence that justifies it.");
        }
        List<String> vanished = DISPOSITIONS.keySet().stream().filter(table -> !catalogue.contains(table))
                .toList();
        if (!vanished.isEmpty()) {
            throw new IllegalStateException("TenantBundlePlan judges platform table(s) " + vanished
                    + " that the catalogue does not have. A judgement about a table nobody dropped on"
                    + " purpose is a judgement that has drifted, and it would silently absorb the day a"
                    + " table of that name came back meaning something else.");
        }
        return restoreOrder(catalogue).stream().map(DISPOSITIONS::get).toList();
    }

    /**
     * The catalogue re-ordered parents-first, from {@code pg_constraint} rather than from a list here.
     *
     * <p>The same shape {@code TenantExtractionService.restoreOrder} applies to the tenant schema, and
     * deliberately a second implementation rather than a shared one: that one walks a table's foreign
     * key to ROUTE its rows to an organization as well as to order them, and it reads
     * {@code current_schema()} because the schema it walks has no name the binary may know. This one
     * only orders, and only ever inside {@code platform}. Merging them would make the tenant walk take a
     * schema argument it must never be given.
     *
     * <p>Alphabetical at the top level and depth-first below it, with {@code ordered} doubling as the
     * cycle guard, so two runs against the same catalogue produce the same bundle.
     */
    private List<String> restoreOrder(List<String> catalogue) {
        Map<String, List<String>> parents = new LinkedHashMap<>();
        RowCallbackHandler handler = resultSet ->
                parents.computeIfAbsent(resultSet.getString("child_table"), table -> new ArrayList<>())
                        .add(resultSet.getString("parent_table"));
        jdbc.query("""
                select child.relname as child_table, parent.relname as parent_table
                  from pg_constraint con
                  join pg_class child on child.oid = con.conrelid
                  join pg_namespace cn on cn.oid = child.relnamespace
                  join pg_class parent on parent.oid = con.confrelid
                  join pg_namespace pn on pn.oid = parent.relnamespace
                 where con.contype = 'f' and cn.nspname = ? and pn.nspname = ?
                 order by child.relname, con.conname
                """, handler, PLATFORM_SCHEMA, PLATFORM_SCHEMA);
        Set<String> ordered = new LinkedHashSet<>();
        catalogue.forEach(table -> visit(table, catalogue, parents, ordered, new LinkedHashSet<>()));
        return List.copyOf(ordered);
    }

    private static void visit(String table, List<String> catalogue, Map<String, List<String>> parents,
            Set<String> ordered, Set<String> onThePath) {
        // `onThePath` is the cycle guard and it is a SECOND set on purpose: a table already emitted must
        // be skipped, but a table currently being expanded must be skipped WITHOUT being emitted, and
        // one set cannot say both. Post-order — a table is emitted after the parents it references — so
        // a chain of three lands deepest-first rather than merely swapping the pair that was compared.
        if (ordered.contains(table) || onThePath.contains(table) || !catalogue.contains(table)) {
            return;
        }
        onThePath.add(table);
        parents.getOrDefault(table, List.of())
                .forEach(parent -> visit(parent, catalogue, parents, ordered, onThePath));
        onThePath.remove(table);
        ordered.add(table);
    }

    /** The tables of one disposition, in restore order. */
    List<PlatformTable> of(BundleDisposition disposition) {
        return plan().stream().filter(table -> table.disposition() == disposition).toList();
    }

    /**
     * <strong>ADR 0010 §6 item 4, made loud.</strong> §6 calls this "the worst" trap in the document and
     * the reason is that the catalogue's absence has <em>two opposite symptoms and no error</em>. Both
     * halves were verified against the source rather than taken from the ADR:
     *
     * <ul>
     *   <li>{@code EntitlementResolver.resolve} falls back to the {@code FREE} plan and, when even that
     *       is missing, returns {@code Map.of()} — its own comment says "seeder not run (fresh empty DB
     *       mid-migration)". An extracted deployment restored without the snapshot is exactly that
     *       state, and nothing distinguishes the two.</li>
     *   <li>From an empty map, {@code EntitlementsImpl.limitOf} returns {@code null} for every key and
     *       {@code requireWithinLimit} does nothing when the limit is null → <b>every quota is
     *       unlimited</b>. In the same map {@code hasFeature} is {@code containsKey}, so it is false for
     *       every key and {@code requireFeature} throws {@code ForbiddenException} → <b>every gated
     *       feature 403s</b>. One missing table both removes every ceiling and closes every door, and
     *       logs neither.</li>
     * </ul>
     *
     * <p>So the bundle refuses to be produced from an installation whose catalogue is not worth
     * shipping. Three tables are refusals and three are warnings, and the line between them is whether
     * empty is a state a healthy deployment can legitimately be in: {@code plan} and
     * {@code plan_entitlement} are written by {@code PlanSeeder} and {@code sla_policy} by
     * {@code SlaPolicySeeder} on every boot, so empty means broken; {@code translation},
     * {@code setting} and {@code feature_flag} have no seeder and an installation that never wrote one
     * is fine — but the far side inherits that emptiness with symptoms worth naming, so they warn.
     *
     * @return the warnings; the refusals throw
     */
    List<String> requireTheCatalogueIsWorthShipping() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (PlatformTable table : of(BundleDisposition.SNAPSHOT_WHOLE_TABLE)) {
            Long rows = jdbc.queryForObject(
                    "select count(*) from " + PLATFORM_SCHEMA + "." + table.table(), Long.class);
            counts.put(table.table(), rows == null ? 0 : rows);
        }
        List<String> empty = MUST_NOT_BE_EMPTY.stream()
                .filter(table -> counts.getOrDefault(table, 0L) == 0)
                .toList();
        if (!empty.isEmpty()) {
            throw new IllegalStateException("the platform catalogue table(s) " + empty + " are EMPTY, so"
                    + " the snapshot this bundle would ship is worthless and its absence on the far side"
                    + " is silent: with no plan resolvable, EntitlementResolver returns an empty map,"
                    + " limitOf returns null for every key and requireWithinLimit does nothing (every"
                    + " quota unlimited) while hasFeature returns false for every key and requireFeature"
                    + " throws (every gated feature 403s). PlanSeeder and SlaPolicySeeder write these on"
                    + " every boot, so an empty one means this installation is broken here and now —"
                    + " fix it before extracting anything (ADR 0010 §6 item 4).");
        }
        List<String> warnings = new ArrayList<>();
        counts.forEach((table, rows) -> {
            if (rows == 0 && !MUST_NOT_BE_EMPTY.contains(table)) {
                warnings.add("platform." + table + " is empty, so the extracted deployment starts with"
                        + " none: " + EMPTY_MEANS.get(table));
            }
        });
        return warnings;
    }

    /**
     * The catalogue tables an installation cannot legitimately have empty, because something writes
     * them on every boot. The other three are legitimately empty and warn instead — see
     * {@link #requireTheCatalogueIsWorthShipping}.
     */
    private static final List<String> MUST_NOT_BE_EMPTY = List.of("plan", "plan_entitlement", "sla_policy");

    /** What the far side loses per legitimately-empty catalogue table. Printed, never inferred. */
    private static final Map<String, String> EMPTY_MEANS = Map.of(
            "translation", "message keys on the wire instead of sentences",
            "setting", "every configurable value falls back to its compiled-in default",
            "feature_flag", "no flag exists, so every flag-gated path takes its off branch");

    /**
     * Every table that stays behind, with the sentence that says what the tenant loses. Printed into
     * the manifest — see the class note on why this list is a deliverable rather than a by-product.
     */
    List<String> whatDoesNotTravel() {
        return plan().stream()
                .filter(table -> table.disposition() == BundleDisposition.STAYS_ON_THE_PLATFORM)
                .map(table -> "platform." + table.table() + " — " + table.why())
                .toList();
    }

    /** The tables a bundle is structurally incapable of carrying. Item 6, enumerable rather than recited. */
    List<String> freshAndEmpty() {
        return of(BundleDisposition.FRESH_AND_EMPTY).stream().map(PlatformTable::table).toList();
    }

    /**
     * The tables the DESTINATION writes for itself — no rows carried, but a row required before it can
     * serve. Distinct from {@link #freshAndEmpty()}, and the distinction is the whole reason this method
     * exists rather than the two dispositions being one: fresh-and-empty is satisfied by doing nothing,
     * and this is not. {@code tenant_placement} left unwritten routes the whole tenant to
     * {@code tenant_pool} in silence — see {@code BundleScriptWriter.writeThePlacementRow}, which emits
     * the statement that satisfies it.
     *
     * <p>Derived rather than listed so the day a second WRITTEN_FRESH table is judged here, the manifest
     * names it without anyone remembering to come back — the same reason {@link #freshAndEmpty()} is a
     * query over the plan instead of a sentence.
     */
    List<String> writtenFresh() {
        return of(BundleDisposition.WRITTEN_FRESH).stream().map(PlatformTable::table).toList();
    }

    private static Map<String, PlatformTable> dispositions() {
        Map<String, PlatformTable> tables = new LinkedHashMap<>();

        // ---- §6 item 2: the routing rows, seeded into the new deployment's own platform schema ------
        // organization.id IS the tenant key and it does not change on extraction (§2.2), which is what
        // keeps every created_by/updated_by soft ref meaningful on the far side.
        put(tables, "organization", BundleDisposition.SEEDED_FOR_THIS_ORG, "id = ?",
                "the tenant's own routing row");
        // The identifiers the IdP knows this tenant by. The JWT `organization` claim resolves HERE
        // before any tenant is known (§2), so a deployment without it cannot authenticate anybody.
        put(tables, "external_organization", BundleDisposition.SEEDED_FOR_THIS_ORG, "organization_id = ?",
                "the identifiers the token's org claim resolves through");

        // ---- §6 item 5: the sessions the tenant's audit rows point at -------------------------------
        // audit_log.impersonation_id is a soft ref to this table. Without these rows an audit entry
        // written during a support session can still be read, but its STATED REASON — the sentence a
        // regulator asks for — resolves to nothing.
        put(tables, "impersonation_session", BundleDisposition.SEEDED_FOR_THIS_ORG, "org_id = ?",
                "the support sessions the tenant's audit rows resolve their stated reason through");

        // ---- §6 item 4: the catalogue snapshot, after which the two deliberately diverge ------------
        put(tables, "plan", BundleDisposition.SNAPSHOT_WHOLE_TABLE, null,
                "the plan catalogue; org_subscription.plan_id is a soft ref into it (V53)");
        put(tables, "plan_entitlement", BundleDisposition.SNAPSHOT_WHOLE_TABLE, null,
                "the limits and features each plan carries — the map every quota check reads");
        put(tables, "sla_policy", BundleDisposition.SNAPSHOT_WHOLE_TABLE, null,
                "without it every ticket's due date is null and SLA escalation stops with no log line");
        put(tables, "translation", BundleDisposition.SNAPSHOT_WHOLE_TABLE, null,
                "without it message keys reach the wire instead of sentences");
        put(tables, "setting", BundleDisposition.SNAPSHOT_WHOLE_TABLE, null,
                "the platform's configured values; absent, every one falls back to its compiled default");
        put(tables, "feature_flag", BundleDisposition.SNAPSHOT_WHOLE_TABLE, null,
                "the flag catalogue the tenant's own feature_flag_org_override rows override");

        // ---- §6 item 3: the person projection -------------------------------------------------------
        put(tables, "person", BundleDisposition.PROJECTED, null,
                "id, name, status — never the row (§2.2)");
        put(tables, "person_contact", BundleDisposition.PROJECTED, null,
                "one display contact per person, UNVERIFIED — a verified copy would put two systems in"
                        + " violation of uq_person_contact_verified_live");

        // ---- §6 item 6: fresh and empty, and note the two §6 did not know about ---------------------
        put(tables, "event_publication", BundleDisposition.FRESH_AND_EMPTY, null,
                "copying it replays events the platform already delivered");
        put(tables, "event_inbox", BundleDisposition.FRESH_AND_EMPTY, null,
                "infrastructure; a copied inbox re-consumes what was consumed");
        put(tables, "idempotency_key", BundleDisposition.FRESH_AND_EMPTY, null,
                "transient request bookkeeping keyed on (principal, idem_key)");
        put(tables, "shedlock", BundleDisposition.FRESH_AND_EMPTY, null,
                "a copied lock_until makes the new deployment silently run NO jobs at all");
        // Not in §6's list because it did not exist yet — V56, Phase 3. The lease is a uuid identifying
        // WHO holds a tenant's queue slot; two deployments holding the same one is the same class of
        // failure as a copied shedlock row, and quieter.
        put(tables, "queue_signal", BundleDisposition.FRESH_AND_EMPTY, null,
                "a copied lease hands the new deployment a claim the platform still believes it holds");
        // Also newer than §6 — V58, Phase 5. A freeze row is a pause; copied, it pauses every queue in a
        // deployment where no promotion is running, until its expires_at lapses.
        put(tables, "tenant_freeze", BundleDisposition.FRESH_AND_EMPTY, null,
                "a copied freeze pauses the new deployment's queues for a promotion that is not happening");
        // V60, Phase 7 — newer again, and the clearest vindication of the refuse-at-plan-time rule this
        // class enforces: it arrived in the same session as the guard and the guard caught it, before any
        // bundle could ship without an answer for it. A cutover row is the SOURCE deployment's record of a
        // move it is running — publication, subscription, slot names and the LSN the freeze waited on.
        // Copied, the new deployment inherits a claim on replication objects that live in somebody else's
        // database: its abandoned-slot detector reports slots it cannot see, and a reverse cutover would
        // read a confirmed_flush_lsn belonging to a stream it never opened.
        put(tables, "tenant_cutover", BundleDisposition.FRESH_AND_EMPTY, null,
                "a copied cutover record claims replication objects in a database the new deployment "
                        + "cannot reach, and points its reverse path at somebody else's slot");

        // ---- written fresh / rebuilt ----------------------------------------------------------------
        put(tables, "tenant_placement", BundleDisposition.WRITTEN_FRESH, null,
                "a lifted tenant has exactly one placement, its own — DATA_MODEL.md says so outright");
        put(tables, "search_document", BundleDisposition.REBUILT, null,
                "derived data; the far side reindexes from its own rows (§6 item 10)");
        put(tables, "org_membership_index", BundleDisposition.REBUILT, null,
                "a routing projection of membership; the tenant schema is the authority (§2.1)");
        // V61, Phase 7 — the third platform table this guard has caught arriving without a judgement,
        // after queue_signal and tenant_cutover. REBUILT for the same reason org_membership_index is: it
        // is a projection of tenant-tier `ticket` rows kept on the platform side so an operator can page
        // one queue across every schema, and those rows travel in the tenant's own dump. Carrying the
        // index too would restore a second copy that the far side's reconciler then has to disagree with
        // — and the far side has exactly one tenant, which is the case the cross-tenant index exists to
        // avoid needing.
        put(tables, "ticket_index", BundleDisposition.REBUILT, null,
                "a cross-tenant operator projection of ticket; the tenant's own schema is the authority "
                        + "and its reconciler rebuilds the index from it (§8 Q2)");

        // ---- credentials and identities -------------------------------------------------------------
        put(tables, "api_key", BundleDisposition.REMINTED, null,
                "the platform revokes the org's keys and reserves their prefixes; the far side mints its"
                        + " own (§6 item 8)");
        put(tables, "external_identity", BundleDisposition.REWRITTEN, null,
                "a copy would put two systems in violation of uq_external_identity_subject_live —"
                        + " one-login-one-human, platform-wide (§6 item 9)");
        put(tables, "notification_delivery", BundleDisposition.DRAINED, null,
                "pure transport; the platform delivers the org's queue before it leaves (§6 item 11)");

        // ---- what stays, each with what the tenant loses by it staying -------------------------------
        // The person graph. §2.2: nothing else about the human travels, and the two global uniqueness
        // constraints are why it CANNOT.
        put(tables, "user_device", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "device registrations are the human's, not the org's; the tenant's user_device_trust rows"
                        + " carry the denormalized person_id and fingerprint V51 added, so trust still"
                        + " resolves in one schema");
        put(tables, "person_preference", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "person graph — the human's preferences follow the human, not the org");
        put(tables, "person_profile", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "person graph; the projection carries names, nothing else");
        put(tables, "consent_record", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "person graph; consent is given to the platform and cannot be re-asserted by a copy");
        put(tables, "erasure_request", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "person graph; an erasure is executed where the person graph is");
        put(tables, "in_app_notification", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "addressed to a GLOBAL person, so the tenant's copy would be one half of one inbox");
        // DECIDED, after shipping twice with opposite answers (ADR 0010 §7 Phase 6 recorded the
        // contradiction: this file said STAYS while TenantSchemaSelfContainmentTest asserted TRAVELS and
        // hand-carried the rows itself). The hold stays, and the extraction is what stops: a hold is an
        // instruction to THIS operator to preserve what is in THIS operator's custody, and handing the
        // data to a deployment somebody else runs is not a thing a copied row makes lawful. What DID
        // change is the sentence TenantBundler prints — telling an operator to release a litigation hold
        // so a move may proceed was advice this code had no business giving.
        put(tables, "legal_hold", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "there must be exactly ONE set of holds (§2), and a hold is custody rather than data: a"
                        + " live hold naming this org REFUSES the bundle outright rather than travelling"
                        + " — see TenantBundler, which escalates instead of suggesting a release");
        put(tables, "signup_request", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "the row exists before its tenant does; it is the platform's funnel record, not the"
                        + " tenant's history");
        // V59, and this class's own refusal is what put it here: the table was added by ADR 0010 Phase 6
        // and plan() failed naming it until somebody decided. The decision is that a reservation is a
        // fact about the PLATFORM's 48-bit prefix space — the far side mints into a space of its own,
        // where the platform's burned prefixes forbid nothing and mean nothing.
        put(tables, "api_key_prefix_reservation", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "the platform's own record of which key prefixes may never be minted again; the"
                        + " extracted deployment has its own prefix space and inherits no reservations");
        // Also DECIDED here rather than left to disagree (same §7 Phase 6 note). The billing-continuity
        // argument for carrying it is real and it loses to one column: `exported` is UsageExportJob's
        // half of the double-billing guard ("the exported flag here, KB's trackingId dedup there"), and
        // a copied row arrives with it false — so the far side's own export re-prices days the platform
        // has already invoiced, which is item 6's hazard exactly. An operator who needs the history for
        // a specific tenant takes it by hand and says so in the handover; the bundle will not do it
        // silently.
        put(tables, "api_usage_daily", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "the metered ledger the platform invoices this tenant from. Copying it would give one"
                        + " billing period two ledgers that immediately diverge, and every carried row"
                        + " arrives with exported=false so the far side re-exports days already"
                        + " invoiced; the far side starts its own count at extraction, and any"
                        + " in-period quota measured from it restarts");

        // The PLATFORM half of the seven split tables. Their TENANT half is in the dump — that is the
        // whole point of a split table — so what stays here is the org-less copy, and each NULL means
        // something different (§2, which is why these are seven lines and not one).
        put(tables, "audit_log", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "split: the org's rows are in the tenant dump. The NULL-org rows are the platform's own"
                        + " record — impersonation lifecycle, platform key mint/revoke, flag changes");
        put(tables, "document", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "split: NULL org means a PERSONAL document, which belongs to a human and must not travel");
        put(tables, "exchange_job", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "split: NULL org is a platform-scoped handler's job (V24:11), not the tenant's history");
        put(tables, "exchange_job_error", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "split: follows its parent job, and the parent that stays is the platform's");
        put(tables, "integration", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "split: NULL org is the PLATFORM DEFAULT (V33:15) and holds the platform's own provider"
                        + " credentials. An org relying on the default rather than an override arrives"
                        + " with no provider configured — TenantBundler warns when that is the case");
        put(tables, "integration_setting", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "split: follows its parent integration, and the parent that stays is the platform's");
        put(tables, "maintenance_window", BundleDisposition.STAYS_ON_THE_PLATFORM, null,
                "split: NULL org is a platform-wide window every request reads; there is no platform"
                        + " above the extracted deployment to declare one");

        return Map.copyOf(tables);
    }

    private static void put(Map<String, PlatformTable> tables, String table,
            BundleDisposition disposition, String orgPredicate, String why) {
        if (tables.put(table, new PlatformTable(table, disposition, orgPredicate, why)) != null) {
            throw new IllegalStateException("platform." + table + " is judged twice in TenantBundlePlan,"
                    + " and the second answer silently won");
        }
    }

    /** Sorted table names of one disposition — for tests and for the manifest, which both want stability. */
    static List<String> declared(BundleDisposition disposition) {
        return List.copyOf(new TreeSet<>(DISPOSITIONS.values().stream()
                .filter(table -> table.disposition() == disposition)
                .map(PlatformTable::table)
                .toList()));
    }
}
