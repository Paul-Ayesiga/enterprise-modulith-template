package ug.co.smsone.compliance.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ug.co.smsone.apikeys.ApiKeyReminting;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.deployment.DeploymentIdentity;
import ug.co.smsone.shared.document.OrgObjectPrefixes;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.promotion.TenantFreezes;

/**
 * <strong>The extraction bundle: the eleven things ADR 0010 §6 says an extracted deployment needs,
 * produced as one artifact with one manifest.</strong> Phase 6's headline deliverable.
 *
 * <h2>What this is, against what {@code TenantExtractionService} already was</h2>
 *
 * <p>{@link TenantExtractionService} owns item 1 and owns it completely — the tenant's own schema, with
 * the table list read from the database so a table added in V60 travels by itself. Its own javadoc says
 * where it stops: "<em>this class owns exactly part 1, and owning it completely is what makes the rest
 * checkable</em>". This is the rest. It <b>extends</b> that service rather than replacing it: item 1 is
 * a delegated call, and everything else here is the platform side, which no dump of a tenant schema can
 * ever contain because none of it is in that schema.
 *
 * <p>{@link GdprBundleService} is still the opposite artifact and the boundary between them is
 * unchanged: a disclosure must not contain {@code webhook_subscription.secret} or
 * {@code api_key.secret_hash}, and an extraction that omitted them produces a tenant that does not
 * boot. This class widens the extraction; it never widens the disclosure.
 *
 * <h2>{@code pg_dump -n t_<hex>}: what is generated, and what is deliberately not</h2>
 *
 * <p>§6 writes item 1 as {@code pg_dump -n t_<hex>} and {@code TenantExtractionService} already records
 * why it is not literally that call while a tenant is pooled. There is a second reason, and it survives
 * promotion: <b>the DDL half of a dump is redundant with the migration set, and the data half is not
 * what a faithful dump would produce.</b>
 *
 * <ul>
 *   <li><b>The schema comes from {@code db/migration/tenant/}, not from a dump.</b> The extracted
 *       deployment is the same jar (§6 hop 3→4: "<em>the tenant never becomes a service — the platform
 *       becomes one, and the tenant deployment is the same jar with different wiring</em>"), so its
 *       Flyway builds the tenant schema exactly as {@code TenantProvisioner.materialize} builds every
 *       silo. A dumped schema would arrive with the SOURCE's {@code flyway_schema_history}, which is
 *       another schema's applied-version record wearing this one's name — the same reason
 *       {@code TenantExtractionService} and {@code TenantTierTables} both exclude that table.</li>
 *   <li><b>The data half cannot be a faithful dump anyway.</b> Six of the eleven items are
 *       transformations, not copies: keys re-minted, identities rewritten, the search index rebuilt,
 *       the notification queue drained, four-plus infrastructure tables fresh, the person graph reduced
 *       to a projection. A tool whose contract is "everything, byte for byte" is the wrong tool for an
 *       artifact whose contract is "everything the tenant owns, and nothing that would be true twice".</li>
 *   <li><b>And the binary is not there.</b> {@code pg_dump} is a client program, version-coupled to the
 *       server (a 16 client refuses an 18 server), absent from the distroless runtime image, and would
 *       need shell-out plus credentials from inside the application. An operator running
 *       {@code pg_dump -n t_<hex>} by hand for the tenant-schema half is a perfectly good runbook step
 *       and {@code TenantSchemaSelfContainmentTest} exercises exactly that path; what an operator
 *       cannot do by hand is the ten platform-side items, which is why they are what this class
 *       produces.</li>
 * </ul>
 *
 * <h2>Where it lives, and why that is not a module violation</h2>
 *
 * <p>{@code compliance.internal}, next to the two artifacts it is a sibling of. The alternative was
 * {@code shared.tenancy.extraction}, and it is not available: this class builds on
 * {@code TenantExtractionService}, which is package-private in this package, and AGENTS §2.2 forbids
 * {@code shared} from compile-depending on a business module at all — so a bundler in {@code shared}
 * could only reach the extraction by inverting it behind a port, which would put the interface in the
 * OWNER's world rather than the consumer's and invert §2.3's rule to no benefit.
 *
 * <p>It reads many modules' tables and imports none of their internals. Every platform table is reached
 * through {@code JdbcTemplate} SQL — the same way {@code GdprBundleService} already reads
 * {@code organization}, {@code api_key} and {@code ticket}, and the same way
 * {@code shared.tenancy.promotion} reads all 27 tenant tables. The one Java dependency it adds is
 * {@link ApiKeyReminting}, which is a port in the {@code apikeys} module's API package: item 8 has to
 * revoke keys through the entity's {@code @SQLDelete}, and reaching into {@code apikeys.internal} to do
 * it is exactly what {@code ModularityTests} forbids.
 *
 * <h2>REHEARSAL and CUTOVER, because one of the eleven items is destructive</h2>
 *
 * <p>Item 8 revokes the tenant's live API keys. Everything else here reads. Phase 6 is called the
 * extraction <em>rehearsal</em>, and a rehearsal that killed the tenant's machine credentials would be
 * a rehearsal nobody runs twice — so {@link Mode#REHEARSAL} reports what would be revoked and revokes
 * nothing, and {@link Mode#CUTOVER} does it. The manifest says which it was, in every case, because a
 * bundle you cannot tell apart from a rehearsal is a bundle whose keys you do not know the state of.
 *
 * <h2>No HTTP route, for the reasons {@code TenantExtractionService} already gives</h2>
 *
 * <p>Unbounded, credential-bearing, and destructive in {@link Mode#CUTOVER}. It is an operator runbook
 * step driven from {@code docs/runbooks/tenant-extraction.md}.
 */
@Service
class TenantBundler {

    private static final Logger log = LoggerFactory.getLogger(TenantBundler.class);

    /**
     * §6 item 9's decision, recorded rather than deferred. Federating to the platform issuer copies
     * nothing and needs no re-link event per member, which is the only form in which
     * {@code uq_external_identity_subject_live} — one-login-one-human, platform-wide — is not violated
     * by the act of extraction itself. Owning an issuer later is a migration the extracted deployment
     * performs on its own schedule, with a re-link event per member; it is not something a bundle can
     * carry, because the new subjects do not exist when the bundle is made.
     */
    private static final String IDP_DECISION =
            "FEDERATE to the platform issuer. Nothing is copied: external_identity is rewritten on the "
            + "far side, never carried, because a copied row puts two systems in violation of "
            + "uq_external_identity_subject_live. The extracted deployment keeps the platform's issuer "
            + "in its OIDC configuration and resolves the same subjects to the same person ids — which "
            + "works because person.id does not change on extraction (ADR 0010 §2.2). Moving to an "
            + "issuer of its own is a later migration with a re-link event per member, and it is out of "
            + "scope for the bundle by construction (§6 item 9, §8 Q8).";

    /** One row of the bundle, handed over as it is read. See {@code TenantExtractionService.Sink}. */
    @FunctionalInterface
    interface Sink {
        /**
         * @param schema {@code "platform"} for a row the far side seeds into its own platform schema,
         *     {@code null} for a tenant-schema row — which is unqualified on purpose, because which
         *     schema it means is the destination's business (ADR 0010 §2)
         */
        void row(BundlePart part, String schema, String table, Map<String, Object> row);
    }

    /** Whether item 8 actually revokes. See the class note. */
    enum Mode {
        /** Reads only. Reports the keys a cutover would revoke and revokes none of them. */
        REHEARSAL,
        /** The real thing: keys revoked, prefixes reserved permanently. */
        CUTOVER
    }

    /** What one of §6's eleven items produced. */
    record PartReport(BundlePart part, BundleDisposition disposition, int rows, String note) {
    }

    /**
     * The bundle's own account of itself.
     *
     * <p><b>A manifest that is missing one of §6's eleven items cannot be constructed.</b> The compact
     * constructor requires exactly one {@link PartReport} per {@link BundlePart} and sorts them into the
     * document's order. Both halves are load-bearing and neither is decoration. The completeness check
     * is what makes "did the bundle produce all of it" a property of the type rather than a checklist
     * somebody reads: a part quietly dropped from {@link #bundle} — the easiest edit in this file to
     * make by accident — fails here instead of shipping a bundle that is short by one item and says
     * nothing. The sort is what makes {@link BundlePart#ordinal()} mean what its javadoc claims: the
     * parts are produced in the order the WORK has to happen (the tenant's own axis first, the
     * projection after the rows that name its people), which is not §6's editorial order, and a manifest
     * an operator cannot read side by side with the document is a manifest they will read against the
     * wrong item.
     *
     * <p><b>{@link #requireComplete()} is where §6 item 7 stops being someone else's problem.</b> The
     * object store is another owner's subsystem and this class deliberately does not extract it — but a
     * bundle whose bytes were never taken is a tenant that boots, lists its documents, and 404s every
     * download. So the manifest arrives with the two prefixes recorded and the part UNACKNOWLEDGED, and
     * {@link #requireComplete()} throws until {@link #withObjectBytes} says otherwise. That is a seam
     * that cannot be forgotten, as opposed to a sentence in a runbook that can.
     */
    record Manifest(UUID orgId, String tenantSchema, Mode mode, String sourceDeployment,
            List<PartReport> parts, List<String> objectPrefixes, ObjectBytes objectBytes,
            List<String> reservedPrefixes, String identityProviderDecision, List<String> doesNotTravel,
            List<String> warnings) {

        Manifest {
            parts = inDocumentOrder(parts);
            objectPrefixes = List.copyOf(objectPrefixes);
            reservedPrefixes = List.copyOf(reservedPrefixes);
            doesNotTravel = List.copyOf(doesNotTravel);
            warnings = List.copyOf(warnings);
        }

        private static List<PartReport> inDocumentOrder(List<PartReport> reported) {
            Map<BundlePart, PartReport> byPart = new LinkedHashMap<>();
            for (PartReport report : reported) {
                if (byPart.put(report.part(), report) != null) {
                    throw new IllegalStateException("ADR 0010 §6 item " + (report.part().ordinal() + 1)
                            + " (" + report.part() + ") is reported twice in one manifest, and the"
                            + " second answer silently won");
                }
            }
            List<BundlePart> missing = Arrays.stream(BundlePart.values())
                    .filter(part -> !byPart.containsKey(part)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("this manifest accounts for " + byPart.size() + " of ADR"
                        + " 0010 §6's " + BundlePart.values().length + " parts — " + missing + " went"
                        + " unreported. Every part is an answer, including the ones whose answer is"
                        + " 'nothing travels': an unreported part is indistinguishable from a part that"
                        + " produced nothing, and the operator restoring this bundle has no other source"
                        + " for the difference.");
            }
            return Arrays.stream(BundlePart.values()).map(byPart::get).toList();
        }

        /** The object-store extractor's report, handed back by whoever ran it. */
        record ObjectBytes(long objects, long bytes) {
        }

        /** Records that the org's bytes were taken under {@link #objectPrefixes()}. */
        Manifest withObjectBytes(ObjectBytes taken) {
            if (taken == null) {
                throw new IllegalArgumentException("null is not an acknowledgement — ADR 0010 §6 item 7"
                        + " is satisfied by a count, even a count of zero, from whoever ran the"
                        + " extractor");
            }
            return new Manifest(orgId, tenantSchema, mode, sourceDeployment, parts, objectPrefixes,
                    taken, reservedPrefixes, identityProviderDecision, doesNotTravel, warnings);
        }

        /**
         * <strong>ADR 0010 §6 hop 2→3's non-table half, carried as a decision rather than as a value.</strong>
         * §6 item 6 names four tables that must be fresh and then adds one sentence more: "<em>Also
         * fresh: the Valkey cache/rate-limit key prefixes and the SeaweedFS bucket root.</em>" The
         * tables are safe because {@code TenantBundlePlan} made them structural — the disposition
         * carries no rows and there is no SQL that could ask for them. These two cannot be made
         * structural by the same trick, because they are not in this database at all: they are the
         * destination's configuration, and a bundle cannot reach into it.
         *
         * <p>So what travels is the RULE and the source's own name — never a namespace to paste. A
         * bundle that printed the destination's prefix would be a bundle whose prefix gets pasted
         * twice, which is the failure it exists to prevent.
         */
        String freshInfrastructureIdentity() {
            return "the destination MUST run with an app.deployment.id of its own — anything except '"
                    + sourceDeployment + "', which is this bundle's source. That one value is what"
                    + " gives a deployment its Valkey cache and rate-limit namespace (dep:<id>:…) and"
                    + " its object-store bucket (<app.storage.bucket>-dep-<id>). Left at the source's,"
                    + " the two deployments share both: the source's eviction clears the destination's"
                    + " cache entry, the source's CACHED VALUE answers the destination's read, one"
                    + " tenant's rate-limit quota is spent by two deployments, and the source's"
                    + " SoftDeletePurgeJob deletes objects the destination is serving — because both"
                    + " sides hold byte-identical object keys by design (ADR 0010 §2.2 keeps"
                    + " organization.id, so document.storage_key travels verbatim). Every one of those"
                    + " is silent on both sides.";
        }

        /**
         * @throws IllegalStateException while any of §6's eleven parts is unaccounted for. Today that
         *     is only the object store, and it is the one part this class does not produce.
         */
        void requireComplete() {
            if (objectBytes == null) {
                throw new IllegalStateException("this bundle is NOT complete: nobody has reported taking"
                        + " organization " + orgId + "'s bytes from the object store under "
                        + objectPrefixes + " (ADR 0010 §6 item 7). Those two prefixes are the complete"
                        + " set — every org-scoped key carries an o/<orgId>/ segment and NewDocument"
                        + " refuses one that does not — and everything else in the store is"
                        + " person-scoped and stays. A tenant restored without them boots, lists its"
                        + " documents and 404s every download. Run the object-store extractor and call"
                        + " withObjectBytes(...) with what it took.");
            }
        }

        int rowsBundled() {
            return parts.stream().mapToInt(PartReport::rows).sum();
        }
    }

    private final JdbcTemplate jdbc;
    private final TenantExtractionService extraction;
    private final TenantBundlePlan plan;
    private final PersonProjector projector;
    private final TenantFreezes freezes;
    private final ApiKeyReminting apiKeys;
    private final AuditLog auditLog;
    /** Only ever asked for its NAME — see {@link Manifest#freshInfrastructureIdentity()}. */
    private final DeploymentIdentity deployment;

    TenantBundler(JdbcTemplate jdbc, TenantExtractionService extraction, TenantBundlePlan plan,
            PersonProjector projector, TenantFreezes freezes, ApiKeyReminting apiKeys,
            AuditLog auditLog, DeploymentIdentity deployment) {
        this.jdbc = jdbc;
        this.extraction = extraction;
        this.plan = plan;
        this.projector = projector;
        this.freezes = freezes;
        this.apiKeys = apiKeys;
        this.auditLog = auditLog;
        this.deployment = deployment;
    }

    /**
     * Produces the bundle for one organization, streaming every row it carries to {@code sink}.
     *
     * <p>Runs on the PLATFORM axis and enters the tenant's axis explicitly for item 1 — the same shape
     * {@code TenantPromoter} uses, and for the same reason: most of this bundle is platform-tier, and a
     * caller already pinned to the tenant would be one {@code TenantContext.set} away from a silent
     * misroute inside a transaction it did not open.
     *
     * <p>Not {@code @Transactional}, deliberately, and the argument is {@code TenantExtractionService}'s
     * verbatim: one repeatable-read snapshot across a whole tenant pins the oldest xmin cluster-wide and
     * blocks vacuum for every other tenant on the box. Consistency comes from the freeze, at the right
     * layer, which is why {@link Mode#CUTOVER} refuses without one.
     *
     * @throws IllegalStateException if a transaction is open, if the thread is on a tenant axis, if the
     *     preconditions in {@link #refuseUnlessTheTenantIsReadyToLeave} are not met, or if any platform
     *     table has no recorded disposition
     */
    Manifest bundle(UUID orgId, Mode mode, Sink sink) {
        if (orgId == null) {
            throw new IllegalArgumentException("a bundle names an organization; null names nothing");
        }
        refuseInsideATransaction();
        refuseOnATenantAxis();

        List<String> warnings = new ArrayList<>(refuseUnlessTheTenantIsReadyToLeave(orgId, mode));
        List<PartReport> parts = new ArrayList<>();
        Set<UUID> people = new LinkedHashSet<>();

        // ---- 8: the keys, and this is FIRST for a reason the manifest's order hides ---------------
        // ApiKeyRemintingService writes one audit row per re-mint, deliberately scoped to the ORG so
        // AuditLogImpl routes it into the tenant's own audit_log — the tenant's record that its machine
        // credentials were retired on the way out. That row only travels if it exists BEFORE item 1
        // reads the tenant schema. Emitted last (which is where §6 lists it) the row is written into a
        // schema the bundle has already finished copying, so a CUTOVER-extracted tenant arrives with no
        // record of the revocation at all — and nothing would ever have said so, because the row is
        // still perfectly present at the ORIGIN, in the schema the origin is about to drop.
        //
        // Doing the destructive step first is also the safer order, not merely the one that works.
        // CUTOVER refuses an unfrozen tenant, so by here the tenant is frozen and its machine clients
        // are already being turned away; revoking inside that window is when it is meant to happen. And
        // revokeAndReserve is idempotent — a second run finds nothing live, reserves nothing new and
        // burns no prefix twice — so an extraction that dies after this point is re-runnable, which is
        // what a runbook step has to be. The reverse order is the one that is not recoverable: it hands
        // the operator a complete-looking script while the old keys are still valid on both sides.
        //
        // REHEARSAL revokes nothing and writes no audit row, so this ordering is invisible there.
        ApiKeyReminting.Reminted keys = remint(orgId, mode);
        parts.add(new PartReport(BundlePart.API_KEYS, BundleDisposition.REMINTED, 0,
                mode == Mode.CUTOVER
                        ? keys.revoked() + " key(s) revoked and their prefixes reserved permanently;"
                                + " the far side mints its own, and the audit row naming the revocation"
                                + " is inside the tenant schema item 1 copies below"
                        : "REHEARSAL: " + keys.revoked() + " live key(s) would be revoked at cutover."
                                + " Nothing was revoked and no prefix was reserved"));

        // ---- 1: the tenant's own schema, on the tenant's own axis --------------------------------
        String tenantSchema = TenantRoutes.homeOf(orgId);
        TenantExtractionService.Manifest tenant = TenantContext.callAs(orgId, () -> {
            // Before a single row moves: prove the person-reference convention still holds in this
            // schema. A stray uuid column is a human the far side renders as a bare id, and the cheapest
            // moment to find that out is before the bundle exists rather than after it is restored.
            projector.requireEveryUuidColumnIsAccountedFor();
            TenantExtractionService.Manifest extracted = extraction.extract(orgId,
                    (table, row) -> sink.row(BundlePart.TENANT_SCHEMA, null, table, row));
            people.addAll(projector.personIdsInTheTenantSchema(extraction.plan(), orgId));
            return extracted;
        });
        parts.add(new PartReport(BundlePart.TENANT_SCHEMA, BundleDisposition.SEEDED_FOR_THIS_ORG,
                tenant.rows().values().stream().mapToInt(Integer::intValue).sum(),
                tenant.tables().size() + " tables out of " + tenantSchema
                        + ", read from the catalogue and not from a list"));

        // ---- 2 and 5: the platform rows this organization is named in -----------------------------
        List<TenantBundlePlan.PlatformTable> seeded =
                plan.of(BundleDisposition.SEEDED_FOR_THIS_ORG);
        people.addAll(projector.personIdsInTheSeededPlatformRows(seeded, orgId));
        Map<String, Integer> seededRows = new LinkedHashMap<>();
        for (TenantBundlePlan.PlatformTable table : seeded) {
            seededRows.put(table.table(), emit(BundlePart.ROUTING_ROWS, table, sink, orgId));
        }
        // Item 5 is one of the SEEDED tables mechanically and a separate line in §6 editorially, so the
        // report splits them back out — a manifest that reads alongside the document is the point.
        // The placement row is item 2's third routing fact and the only one with no rows to carry: the
        // destination writes it for itself (WRITTEN_FRESH), and BundleScriptWriter emits the statement
        // that does. It is reported HERE rather than left to the script because a bundle taken through
        // some other sink still has to tell its operator that the tenant is unroutable until that row
        // exists — TenantRoutes answers `tenant_pool` for an organization the registry does not name,
        // and on the far side that is an empty schema the migration set just built (ADR 0010 §1).
        parts.add(new PartReport(BundlePart.ROUTING_ROWS, BundleDisposition.SEEDED_FOR_THIS_ORG,
                seededRows.getOrDefault("organization", 0)
                        + seededRows.getOrDefault("external_organization", 0),
                "organization + external_organization, into the far side's own platform schema; "
                        + String.join(", ", plan.writtenFresh()) + " is written FRESH there, naming"
                        + " whichever schema the restore's search_path points at — without it every"
                        + " statement routes to the destination's empty tenant_pool and nothing says so"));
        parts.add(new PartReport(BundlePart.IMPERSONATION_SESSIONS,
                BundleDisposition.SEEDED_FOR_THIS_ORG,
                seededRows.getOrDefault("impersonation_session", 0),
                "so audit rows written inside a support session can still resolve their stated reason"));

        // ---- 3: the person projection -------------------------------------------------------------
        Map<String, Integer> projected = projector.project(people, sink);
        parts.add(new PartReport(BundlePart.PERSON_PROJECTION, BundleDisposition.PROJECTED,
                projected.values().stream().mapToInt(Integer::intValue).sum(),
                people.size() + " person id(s) referenced by this tenant, projected as "
                        + projected.get("person") + " person rows and " + projected.get("person_contact")
                        + " UNVERIFIED contacts. The person graph itself stays (ADR 0010 §2.2)"));

        // ---- 4: the catalogue snapshot, §6's worst trap --------------------------------------------
        int catalogue = 0;
        for (TenantBundlePlan.PlatformTable table : plan.of(BundleDisposition.SNAPSHOT_WHOLE_TABLE)) {
            catalogue += emit(BundlePart.CATALOG_SNAPSHOT, table, sink, orgId);
        }
        parts.add(new PartReport(BundlePart.CATALOG_SNAPSHOT, BundleDisposition.SNAPSHOT_WHOLE_TABLE,
                catalogue, "plan, plan_entitlement, sla_policy, translation, setting, feature_flag —"
                        + " verified non-empty before this bundle was allowed to exist, because their"
                        + " absence makes every quota unlimited AND every gated feature 403 at once,"
                        + " with no error either way"));

        // ---- 6: the tables the bundle is INCAPABLE of carrying, and the two things that are not tables
        // §6 item 6 ends with a sentence the table list hides: "Also fresh: the Valkey cache/rate-limit
        // key prefixes and the SeaweedFS bucket root." Those two cannot be made structural the way the
        // tables were — they are the DESTINATION's configuration, which no bundle taken here can reach
        // — so the part carries the rule and the source's own name instead. See
        // Manifest.freshInfrastructureIdentity for why it names what the destination must NOT be rather
        // than handing it a namespace to paste.
        parts.add(new PartReport(BundlePart.FRESH_INFRASTRUCTURE, BundleDisposition.FRESH_AND_EMPTY, 0,
                "created empty by the migration set and unreadable by this bundler: "
                        + String.join(", ", plan.freshAndEmpty())
                        + ". Not tables, and not carried either — " + deployment.id()
                        + " is the deployment this bundle came out of, and the destination needs its own"
                        + " app.deployment.id for its Valkey namespace and its object bucket"));

        // ---- 7: the object store, which this class does not extract --------------------------------
        // Read from OrgObjectPrefixes rather than spelled again here, and that is the difference between
        // a manifest and a comment: the same declaration is the object extraction's argument list, the
        // registration guard's rule and the restore's per-object check, so a root added there is a root
        // this manifest names on the same day. A second copy would let the bundle promise two prefixes
        // while the extractor took three, and the gap would surface as a download that 404s.
        List<String> prefixes = OrgObjectPrefixes.forOrg(orgId);
        parts.add(new PartReport(BundlePart.OBJECT_STORE, BundleDisposition.SEEDED_FOR_THIS_ORG, 0,
                "NOT produced here — run the object-store extractor over " + prefixes
                        + " and acknowledge it with Manifest.withObjectBytes(...)"));

        // ---- 8 was taken before item 1; see the block at the top of this method -------------------

        // ---- 9, 10, 11: the decisions and the rebuilds ---------------------------------------------
        parts.add(new PartReport(BundlePart.IDENTITY_PROVIDER, BundleDisposition.REWRITTEN, 0,
                IDP_DECISION));
        parts.add(new PartReport(BundlePart.SEARCH_INDEX, BundleDisposition.REBUILT, 0,
                countPlatform("search_document", orgId) + " platform search rows for this org are NOT"
                        + " carried; the far side reindexes from its own tenant rows (§6 item 10)"));
        parts.add(new PartReport(BundlePart.QUEUES, BundleDisposition.DRAINED, 0,
                "notification_delivery drained by the platform and not carried; webhook_delivery and"
                        + " exchange_job travel in the dump with no in-flight row, which was verified"
                        + " before this bundle was allowed to exist (§6 item 11)"));

        Manifest manifest = new Manifest(orgId, tenantSchema, mode, deployment.id(), parts, prefixes,
                null, keys.reservedPrefixes(), IDP_DECISION, plan.whatDoesNotTravel(), warnings);
        // Distinct from compliance.tenant_extracted (item 1's own row) and from compliance.org_exported
        // (the disclosure bundle's). Three artifacts, three actions: the only question anyone will ever
        // ask this trail is which of them left the database and when, and an action name that could not
        // tell them apart would be unable to answer it.
        auditLog.record("compliance.tenant_bundled", orgId, orgId.toString(), null,
                "mode=" + mode + " schema=" + tenantSchema + " parts=" + parts.size()
                        + " rows=" + manifest.rowsBundled() + " keysRevoked=" + keys.revoked());
        log.info("bundled organization {} out of {} in {} mode: {} rows across {} parts{}",
                orgId, tenantSchema, mode, manifest.rowsBundled(), parts.size(),
                warnings.isEmpty() ? "" : " WITH WARNINGS: " + warnings);
        return manifest;
    }

    /**
     * Everything that has to be true before a bundle may exist. Every one of these is a refusal rather
     * than a warning because every one of them produces a bundle that looks fine and is not.
     *
     * @return warnings — the things worth saying that do not invalidate the bundle
     */
    private List<String> refuseUnlessTheTenantIsReadyToLeave(UUID orgId, Mode mode) {
        List<String> warnings = new ArrayList<>();

        // The freeze. TenantExtractionService's javadoc is explicit that its own consistency argument
        // is "the runbook froze the tenant", and this is the class that can actually check it. A cutover
        // without one straddles writes and there is no later step that would notice; a rehearsal without
        // one still proves the STRUCTURE, which is what a rehearsal is for, so it warns.
        if (!freezes.isFrozen(orgId)) {
            String unfrozen = "organization " + orgId + " is not frozen (platform.tenant_freeze), so"
                    + " background workers and HTTP writes can land rows while this bundle is being"
                    + " read — the bundle can straddle a write and nothing downstream would detect it";
            if (mode == Mode.CUTOVER) {
                throw new IllegalStateException(unfrozen + ". A CUTOVER bundle is the tenant's only copy"
                        + " of itself; freeze it first (ADR 0010 §6 hop 0->1 names both halves: the"
                        + " RESTRICT maintenance window for HTTP and platform.tenant_freeze for the"
                        + " queues and jobs). REHEARSAL mode accepts an unfrozen tenant and says so.");
            }
            warnings.add(unfrozen + " — accepted because this is a REHEARSAL");
        }

        // A legal hold naming this org. §6 does not list this and the omission is worth closing here:
        // legal_hold is PLATFORM-tier and stays (§2, "there must be exactly ONE set of holds"), so an
        // org extracted out from under a live hold arrives in a deployment whose SoftDeletePurgeJob can
        // see no hold at all and will hard-delete data a court said to keep. That is the exact failure
        // legal_hold's own javadoc narrates a previous version of.
        //
        // THE MESSAGE IS PART OF THE FIX, not decoration. This refusal used to end "Release the hold, or
        // do not extract this tenant" — an instruction to clear a litigation hold so that a move may
        // proceed, printed by the system as if it were one of two equivalent options. It is not: the
        // hold is the obligation and the extraction is the discretionary act, so the only thing this
        // code may tell an operator to stop is the extraction. The runbook says the same in the same
        // words (docs/runbooks/tenant-extraction.md step 3) and neither will offer the other branch.
        Long held = jdbc.queryForObject("""
                select count(*) from platform.legal_hold where org_id = ? and released_at is null
                """, Long.class, orgId);
        if (held != null && held > 0) {
            throw new IllegalStateException("organization " + orgId + " is under " + held + " live legal"
                    + " hold(s) and will not be bundled. Holds are platform-tier and there must be"
                    + " exactly ONE set of them (ADR 0010 §2), so they cannot travel — and an extracted"
                    + " deployment that cannot see a hold will let SoftDeletePurgeJob hard-delete data"
                    + " the hold exists to keep. STOP THE EXTRACTION, not the hold: whether a hold may"
                    + " be released is a decision for counsel and never a step taken to unblock a move,"
                    + " so escalate and bundle this tenant once the hold is lawfully gone.");
        }

        // §6 item 11. Three queues, two different rules, and the difference is whether the row is in the
        // dump. notification_delivery is NOT carried, so anything not yet terminal would be lost outright
        // — the platform has to finish it. webhook_delivery and exchange_job ARE carried, so a PENDING
        // row travels correctly and only an IN-FLIGHT one is the problem: a claimed row in the dump is
        // reclaimed by the far side's stale-lock arm and runs a second time, on the other side of the
        // world, against the tenant's endpoint.
        refuseOnUndrainedQueue("platform.notification_delivery", orgId,
                List.of("PENDING", "PROCESSING"), true,
                "these rows are NOT carried by the bundle, so anything still queued when the tenant"
                        + " leaves is delivered by nobody. Let the platform drain them (§6 item 11)");
        refuseOnUndrainedQueue("webhook_delivery", orgId, List.of("PROCESSING"), false,
                "a claimed row travels in the dump and the far side's stale-lock reclaim will run it"
                        + " again — the same webhook POSTed twice from two deployments. Let the"
                        + " in-flight claims finish before taking the bundle");
        refuseOnUndrainedQueue("exchange_job", orgId, List.of("VALIDATING", "PROCESSING"), false,
                "a claimed job travels in the dump and would be resumed on both sides. PENDING jobs are"
                        + " fine — they travel and run once, on the far side");

        // The catalogue snapshot, and the org's own plan inside it.
        warnings.addAll(plan.requireTheCatalogueIsWorthShipping());
        requireTheOrgsPlanIsInTheSnapshot(orgId);

        // The platform's integration default (§2: `integration` NULL org_id) holds the PLATFORM's own
        // provider credentials, so it stays — correctly, because copying it would hand a tenant the
        // platform's secrets. But an org that never wrote an override has been running on that default,
        // and it arrives configured with nothing. Loud, not fatal: the far side can configure its own.
        Long overrides = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from integration where org_id = ? and deleted_at is null",
                Long.class, orgId));
        if (overrides == null || overrides == 0) {
            warnings.add("organization " + orgId + " has no integration override of its own, so it has"
                    + " been using the PLATFORM DEFAULT (integration with a NULL org_id, V33:15). That"
                    + " row holds the platform's own provider credentials and stays behind — the"
                    + " extracted deployment arrives with no integration configured at all and must be"
                    + " given its own before anything integrated works");
        }
        return warnings;
    }

    /**
     * <strong>§6 item 4's sharpest edge, and it is sharper than the ADR states.</strong> §6 describes
     * the missing-catalogue failure as "every quota unlimited, every feature 403". There is a third
     * symptom, quieter than both, and it is the one a correct bundle actually prevents:
     * {@code EntitlementResolver.resolve} looks up {@code plan} by the subscription's {@code plan_id}
     * and, failing that, falls back to {@code plans.findByCode("FREE")}. A deployment whose catalogue
     * was re-seeded rather than snapshotted has a {@code FREE} plan with a NEW uuid, so the tenant's
     * {@code org_subscription.plan_id} matches nothing and the org is silently served the FREE
     * entitlements — an ENTERPRISE tenant downgraded with no error, no log line and no 403 to
     * investigate.
     *
     * <p>{@code org_subscription.plan_id → plan.id} is one of the five boundary foreign keys ADR 0010
     * §6 had to cut, so the database will not catch this either. This check is what the constraint used
     * to be.
     */
    private void requireTheOrgsPlanIsInTheSnapshot(UUID orgId) {
        List<UUID> named = TenantContext.callAs(orgId, () -> jdbc.queryForList("""
                select distinct plan_id from org_subscription
                 where org_id = ? and plan_id is not null and deleted_at is null
                """, UUID.class, orgId));
        List<UUID> missing = named.stream()
                .filter(planId -> Boolean.FALSE.equals(jdbc.queryForObject(
                        "select exists (select 1 from platform.plan where id = ?)", Boolean.class,
                        planId)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("organization " + orgId + " is subscribed to plan(s) "
                    + missing + " that platform.plan does not hold. org_subscription.plan_id is a SOFT"
                    + " ref — one of the five boundary FKs ADR 0010 cut — so nothing in the database"
                    + " enforces this, and the consequence is silent: EntitlementResolver falls back to"
                    + " findByCode(\"FREE\") and serves the tenant the free entitlements with no error"
                    + " anywhere. Fix the catalogue before extracting, or the far side inherits a"
                    + " downgraded tenant that looks healthy.");
        }
    }

    /**
     * @param platformTier whether the table is named {@code platform.<table>} (and so read on this
     *     thread's platform axis) or bare, which means the tenant's copy and needs the tenant's axis
     */
    private void refuseOnUndrainedQueue(String table, UUID orgId, List<String> statuses,
            boolean platformTier, String why) {
        String inList = statuses.stream().map(status -> "'" + status + "'")
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        String sql = "select count(*) from " + table + " where org_id = ? and status in (" + inList + ")";
        Long queued = platformTier
                ? jdbc.queryForObject(sql, Long.class, orgId)
                : TenantContext.callAs(orgId, () -> jdbc.queryForObject(sql, Long.class, orgId));
        if (queued != null && queued > 0) {
            throw new IllegalStateException("organization " + orgId + " still has " + queued + " row(s)"
                    + " in " + table + " with status in " + statuses + ": " + why
                    + ". Nothing has been bundled.");
        }
    }

    /** Streams one platform table's rows into the sink and returns how many there were. */
    private int emit(BundlePart part, TenantBundlePlan.PlatformTable table, Sink sink, UUID orgId) {
        int[] rows = {0};
        // Typed rather than passed bare for the reason TenantExtractionService names: JdbcTemplate.query
        // has a ResultSetExtractor overload of the same shape, and naming the type is what keeps the
        // row-by-row form from resolving to the whole-result one.
        RowCallbackHandler handler = resultSet -> {
            var metaData = resultSet.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= metaData.getColumnCount(); column++) {
                row.put(metaData.getColumnLabel(column), resultSet.getObject(column));
            }
            rows[0]++;
            sink.row(part, TenantBundlePlan.PLATFORM_SCHEMA, table.table(), row);
        };
        if (table.orgPredicate() == null) {
            jdbc.query(table.read(), handler);
        } else {
            jdbc.query(table.read(), handler, orgId);
        }
        return rows[0];
    }

    private ApiKeyReminting.Reminted remint(UUID orgId, Mode mode) {
        if (mode == Mode.CUTOVER) {
            return apiKeys.revokeAndReserve(orgId,
                    "ADR 0010 §6 item 8: extracted to its own deployment");
        }
        Long live = jdbc.queryForObject("""
                select count(*) from platform.api_key where org_id = ? and deleted_at is null
                """, Long.class, orgId);
        return new ApiKeyReminting.Reminted(live == null ? 0 : live.intValue(), List.of());
    }

    private long countPlatform(String table, UUID orgId) {
        Long rows = jdbc.queryForObject(
                "select count(*) from " + TenantBundlePlan.PLATFORM_SCHEMA + "." + table
                        + " where org_id = ?", Long.class, orgId);
        return rows == null ? 0 : rows;
    }

    private static void refuseInsideATransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a bundle must run OUTSIDE a transaction: it pins the"
                    + " tenant's axis for item 1, which TenantContext refuses inside one, and holding a"
                    + " single snapshot across a whole tenant pins the oldest xmin cluster-wide and"
                    + " blocks vacuum for every other tenant on the box.");
        }
    }

    private static void refuseOnATenantAxis() {
        Tenant current = TenantContext.current();
        if (current instanceof Tenant.Org(UUID pinned)) {
            throw new IllegalStateException("a bundle runs on the PLATFORM axis and enters the tenant's"
                    + " own axis where it needs to (ADR 0010 §3.2). This thread is pinned to " + pinned
                    + ", so most of what a bundle reads — the catalogue snapshot, the person"
                    + " projection, the routing rows — would be addressed to a schema that does not"
                    + " hold them.");
        }
    }
}
