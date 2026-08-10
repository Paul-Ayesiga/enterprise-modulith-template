package ug.co.smsone.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.apikeys.ApiKeyReminting;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.promotion.TenantFreezes;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The extraction bundle — ADR 0010 §6's eleven items — against real Postgres.
 *
 * <p><b>What this suite is for.</b> Ten of the eleven items are platform-side, and the platform side is
 * where an extraction fails <em>silently in both directions</em>: a table that should have travelled and
 * did not costs the far side a capability with no error (§6 item 4 is the worst of them, and it removes
 * every quota ceiling and closes every gated door at the same time), and a table that should have stayed
 * and travelled leaks the platform's rows into one tenant's deployment. Neither says anything at the
 * moment it happens. So almost every assertion below is about a REFUSAL — the bundle declining to exist
 * — rather than about a row that arrived, because a refusal is the only failure mode of an extraction
 * that anybody finds in time.
 *
 * <p>The pairing with {@code TenantExtractionTest} is deliberate: that one owns item 1, the tenant's own
 * schema, and this one owns the ten platform-side items and the manifest that says which of the eleven
 * were answered. The pairing with {@code GdprBundleTest} is the older one and is unchanged — the same
 * {@code webhook_subscription} row is redacted there and carried whole here.
 *
 * <p>{@link #theScriptRestoresIntoADatabaseThatHasNeverSeenThePlatform()} is the acceptance test of this
 * class: a second database, built by the real migration sequences, receiving the emitted script and
 * ending up with a tenant whose subscription resolves against a plan. Everything else here is a property
 * of the plan; that one is the property of the artifact.
 */
class TenantBundleTest extends AbstractIntegrationTest {

    @Autowired
    private TenantBundler bundler;

    @Autowired
    private TenantBundlePlan plan;

    @Autowired
    private TenantFreezes freezes;

    @Autowired
    private ApiKeyReminting apiKeys;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    /**
     * Every one of §6's eleven items is answered, and the manifest reads in the document's order.
     *
     * <p>Both halves matter and only one of them is obvious. Completeness is the first: an item nobody
     * reported is indistinguishable from an item that produced nothing, and the operator restoring the
     * bundle has no other source for the difference. The ORDER is the second, and it is not cosmetic —
     * the parts are produced in the order the work has to happen (the tenant's own axis first, the
     * person projection only after the rows that name its people have been read), which is not §6's
     * editorial order. A manifest an operator cannot lay beside the document is one they will read
     * against the wrong item.
     */
    @Test
    void everyOneOfTheDocumentsElevenItemsIsAnsweredAndTheManifestReadsInItsOrder() {
        UUID orgId = seedTenant("Bundled");

        TenantBundler.Manifest manifest = bundle(orgId, TenantBundler.Mode.REHEARSAL);

        assertThat(manifest.parts().stream().map(TenantBundler.PartReport::part))
                .describedAs("the eleven parts of ADR 0010 §6, in the document's order")
                .containsExactly(BundlePart.values());
        assertThat(manifest.mode()).isEqualTo(TenantBundler.Mode.REHEARSAL);
        assertThat(manifest.identityProviderDecision())
                .describedAs("§6 item 9 is a DECISION and the bundle records which one it assumed")
                .contains("FEDERATE")
                .contains("external_identity is rewritten");
    }

    /**
     * The completeness check is structural, so the easiest edit in {@code TenantBundler} to make by
     * accident — dropping a part while refactoring the method that produces it — cannot ship.
     */
    @Test
    void aManifestThatDroppedOneOfTheElevenItemsCannotBeConstructed() {
        List<TenantBundler.PartReport> short_ = new ArrayList<>();
        for (BundlePart part : BundlePart.values()) {
            if (part != BundlePart.CATALOG_SNAPSHOT) {
                short_.add(new TenantBundler.PartReport(part, BundleDisposition.STAYS_ON_THE_PLATFORM,
                        0, "test"));
            }
        }

        assertThatThrownBy(() -> new TenantBundler.Manifest(UUID.randomUUID(), "tenant_pool",
                TenantBundler.Mode.REHEARSAL, "platform", short_, List.of(), null, List.of(), "x",
                List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CATALOG_SNAPSHOT");
    }

    /**
     * <b>ADR 0010 §6 item 6 has a half that is not a table</b> — "<em>Also fresh: the Valkey
     * cache/rate-limit key prefixes and the SeaweedFS bucket root</em>" — and it is the one part of the
     * bundle that cannot be made structural the way the seven tables were, because it lives in the
     * DESTINATION's configuration and no bundle taken here can reach that.
     *
     * <p>So the manifest has to carry the decision, and this pins the two properties that make it a
     * decision rather than a courtesy: it names the deployment the bundle came OUT of (so the operator
     * knows the one value they must not reuse) and it never hands over a namespace to paste — a bundle
     * that printed one would be a bundle whose namespace gets pasted twice, which is the collision it
     * exists to prevent.
     */
    @Test
    void theBundleCarriesTheFreshInfrastructureDecisionAndNotTheSourcesNamespaces() {
        UUID orgId = seedTenant("Namespaces");

        TenantBundler.Manifest manifest = bundle(orgId, TenantBundler.Mode.REHEARSAL);

        assertThat(manifest.sourceDeployment())
                .describedAs("the shipped app.deployment.id, which the destination must NOT reuse")
                .isEqualTo(ug.co.smsone.shared.deployment.DeploymentIdentity.PLATFORM);
        assertThat(manifest.freshInfrastructureIdentity())
                .contains("app.deployment.id")
                .contains(ug.co.smsone.shared.deployment.DeploymentIdentity.PLATFORM)
                .describedAs("the failures are silent on both sides, so the manifest names them")
                .contains("CACHED VALUE")
                .contains("SoftDeletePurgeJob");
        assertThat(manifest.parts().stream()
                .filter(part -> part.part() == BundlePart.FRESH_INFRASTRUCTURE)
                .map(TenantBundler.PartReport::note).findFirst().orElseThrow())
                .describedAs("item 6 reports the tables AND the two things that are not tables")
                .contains("shedlock")
                .contains("app.deployment.id");
    }

    /**
     * §6 item 7 is the one part this class does not produce, and the manifest refuses to call itself
     * complete until whoever ran the object-store extractor says what it took. A count of zero is a
     * legitimate acknowledgement — an org with no documents has no bytes — but silence is not.
     */
    @Test
    void theBundleIsIncompleteUntilSomebodyReportsTakingTheObjectBytes() {
        UUID orgId = seedTenant("Objects");

        TenantBundler.Manifest manifest = bundle(orgId, TenantBundler.Mode.REHEARSAL);

        assertThat(manifest.objectPrefixes())
                .containsExactly("doc/o/" + orgId + "/", "exch/o/" + orgId + "/");
        assertThatThrownBy(manifest::requireComplete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("404s every download");
        assertThatThrownBy(() -> manifest.withObjectBytes(null))
                .isInstanceOf(IllegalArgumentException.class);

        manifest.withObjectBytes(new TenantBundler.Manifest.ObjectBytes(0, 0)).requireComplete();
    }

    /**
     * <b>§6 item 6, made structural.</b> The four tables the document names must arrive fresh and empty,
     * and the failures of copying them are silent and opposite — a copied {@code shedlock} row makes the
     * new deployment run NO jobs at all, a copied {@code event_publication} replays deliveries the
     * platform already made. So this asserts something stronger than "the bundle did not carry them":
     * there is no SQL in the package that could ask for them, and the attempt throws.
     *
     * <p>The catalogue holds SEVEN, not the document's four, and that gap is the argument for deriving
     * the list rather than reciting it: {@code queue_signal} (V56), {@code tenant_freeze} (V58) and
     * {@code tenant_cutover} (V60) all arrived after §6 was written and all carry exactly its hazard.
     */
    @Test
    void theTablesThatMustArriveFreshAreOnesThisBundlerCannotEvenAskFor() {
        UUID orgId = seedTenant("Fresh");
        jdbc.update("insert into platform.shedlock (name, lock_until, locked_at, locked_by)"
                + " values (?, now() + interval '1 hour', now(), 'someone-else')",
                "bundle-test-" + orgId);

        Collected collected = new Collected();
        TenantBundler.Manifest manifest = bundler.bundle(orgId, TenantBundler.Mode.REHEARSAL, collected);

        assertThat(plan.freshAndEmpty())
                .describedAs("derived from the catalogue, which is how the two tables §6 predates got in")
                .contains("event_publication", "event_inbox", "idempotency_key", "shedlock",
                        "queue_signal", "tenant_freeze", "tenant_cutover");
        assertThat(collected.tables())
                .describedAs("not one row of any of them reached the sink")
                .doesNotContainAnyElementsOf(plan.freshAndEmpty());
        assertThat(manifest.parts().stream()
                .filter(part -> part.part() == BundlePart.FRESH_INFRASTRUCTURE).findFirst().orElseThrow()
                .disposition()).isEqualTo(BundleDisposition.FRESH_AND_EMPTY);

        for (TenantBundlePlan.PlatformTable table : plan.of(BundleDisposition.FRESH_AND_EMPTY)) {
            assertThatThrownBy(table::read)
                    .describedAs("platform.%s must have no read behind it at all", table.table())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("a bundle carries no rows of it");
        }
    }

    /**
     * §6 items 2 and 5: the rows the organization is NAMED in travel, and a neighbour's do not. The
     * impersonation session is the interesting half — without it a tenant's audit rows written during a
     * support session survive the move and their stated reason resolves to nothing, which is precisely
     * the question a regulator asks of them.
     */
    @Test
    void thePlatformRowsTheOrganizationIsNamedInTravelAndItsNeighboursDoNot() {
        UUID orgId = seedTenant("Mine");
        UUID neighbour = seedTenant("Theirs");
        UUID operator = insertPerson("Operator One");
        UUID worn = insertPerson("Worn Identity");
        insertSession(orgId, operator, worn, "investigating a failed export");
        insertSession(neighbour, operator, worn, "someone else's investigation");

        Collected collected = new Collected();
        bundler.bundle(orgId, TenantBundler.Mode.REHEARSAL, collected);

        assertThat(collected.platform("organization")).hasSize(1);
        assertThat(collected.platform("organization").getFirst()).containsEntry("id", orgId);
        assertThat(collected.platform("external_organization")).hasSize(1);
        assertThat(collected.platform("impersonation_session")).hasSize(1);
        assertThat(collected.platform("impersonation_session").getFirst())
                .containsEntry("reason", "investigating a failed export");
    }

    /**
     * §6 item 3 and §2.2. The projection is derived from the naming convention across the WHOLE bundle —
     * the tenant's own tables and the platform rows it carries — so a human who appears only as a
     * {@code created_by} on one row, or only as the operator of a support session, still travels. A
     * neighbour's member does not, and nothing else about any of them does.
     */
    @Test
    void thePersonProjectionCarriesEveryHumanTheTenantNamesAndNothingElseAboutThem() {
        UUID orgId = seedTenant("Projected");
        UUID neighbour = seedTenant("Neighbour");
        UUID member = insertPerson("Named By Membership");
        UUID author = insertPerson("Named Only By Created By");
        UUID operator = insertPerson("Named Only By A Session");
        UUID stranger = insertPerson("Named By Nobody Here");
        insertMembership(orgId, member);
        insertWebhook(orgId, author);
        insertSession(orgId, operator, member, "support");
        insertMembership(neighbour, stranger);

        Collected collected = new Collected();
        bundler.bundle(orgId, TenantBundler.Mode.REHEARSAL, collected);

        List<Object> projected = collected.platform("person").stream()
                .map(row -> row.get("id")).toList();
        assertThat(projected).contains(member, author, operator);
        assertThat(projected)
                .describedAs("a human this tenant never names is 463 MB of somebody else's graph")
                .doesNotContain(stranger);
        assertThat(collected.platform("person").getFirst().keySet())
                .describedAs("§2.2: id, the names, status — and the three columns without which the row"
                        + " cannot be inserted on the far side. Nothing else about the human travels")
                .containsExactlyInAnyOrder("id", "status", "invited_at", "formatted_name", "given_name",
                        "family_name", "version", "created_at", "deleted_at");
    }

    /**
     * A verified contact cannot travel as a verified contact: {@code uq_person_contact_verified_live} is
     * one-verified-address-one-human PLATFORM-WIDE, so a faithful copy puts two systems in violation of
     * a rule neither can see the other breaking. The projection drops the column rather than nulling it,
     * which is why this asserts on the KEY SET and not on a value.
     */
    @Test
    void aVerifiedContactTravelsAsAnUnverifiedDisplayProjection() {
        UUID orgId = seedTenant("Contacts");
        UUID member = insertPerson("Has A Verified Address");
        // Unique per run, because the constraint this test is about is GLOBAL: a fixed address here
        // would collide with the row a previous run left, which is the same violation an extraction
        // would commit if it carried verified_at.
        String address = "verified-" + member + "@example.test";
        insertVerifiedContact(member, address);
        insertMembership(orgId, member);

        Collected collected = new Collected();
        bundler.bundle(orgId, TenantBundler.Mode.REHEARSAL, collected);

        Map<String, Object> contact = collected.platform("person_contact").stream()
                .filter(row -> member.equals(row.get("person_id"))).findFirst().orElseThrow();
        assertThat(contact).containsEntry("contact_value", address);
        assertThat(contact.keySet()).doesNotContain("verified_at");
    }

    /**
     * <b>§6 item 4, the document's worst trap, in the form it actually takes.</b> The failure is not
     * usually an empty {@code plan} table — {@code PlanSeeder} runs on every boot and would fill one.
     * It is that a re-seeded catalogue has the same plans under NEW ids, so the tenant's
     * {@code org_subscription.plan_id} matches nothing, {@code EntitlementResolver} falls back to
     * {@code findByCode("FREE")}, and an ENTERPRISE tenant is served the free entitlements with no
     * error, no log line and no failing request. {@code org_subscription.plan_id → plan.id} is one of
     * the five boundary FKs ADR 0010 cut, so the database will not catch it either.
     *
     * <p>So the bundle carries the catalogue whole, ids included, and refuses to be produced from an
     * installation where the two already disagree.
     */
    @Test
    void theCatalogueTravelsWholeAndASubscriptionNamingAPlanItDoesNotHoldRefusesTheBundle() {
        UUID orgId = seedTenant("Entitled");
        UUID planId = jdbc.queryForObject("select id from platform.plan order by rank limit 1",
                UUID.class);
        insertSubscription(orgId, planId);

        Collected collected = new Collected();
        bundler.bundle(orgId, TenantBundler.Mode.REHEARSAL, collected);
        assertThat(collected.platform("plan").stream().map(row -> row.get("id")))
                .describedAs("the org's own plan travels with the id its subscription names")
                .contains(planId);
        assertThat(collected.tables()).contains("plan_entitlement", "sla_policy");

        // Re-pointed rather than added: uq_org_subscription_org_live allows one live subscription per
        // org, and the failure being reproduced is a plan_id that resolves to nothing — which is what a
        // re-seeded catalogue does to the row that is already there.
        UUID dangling = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update(
                "update org_subscription set plan_id = ? where org_id = ?", dangling, orgId));
        try {
            assertThatThrownBy(() -> bundle(orgId, TenantBundler.Mode.REHEARSAL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(dangling.toString())
                    .hasMessageContaining("findByCode(\"FREE\")");
        } finally {
            // Not tidiness: PlanCatalogGuard asks this same question of every schema at
            // ApplicationReadyEvent, so a dangling plan_id left behind would fail the boot of every
            // Spring context the rest of the suite builds — in a file naming neither this test nor the
            // row it left.
            TenantContext.runAs(orgId,
                    () -> jdbc.update("delete from org_subscription where org_id = ?", orgId));
        }
    }

    /**
     * A hold is platform-tier and there must be exactly ONE set of them (§2), so it cannot travel — and
     * an extracted deployment that cannot see a hold runs a {@code SoftDeletePurgeJob} that hard-deletes
     * the data the hold exists to keep. {@code legal_hold}'s own javadoc narrates a previous version of
     * that bug; this is the refusal that stops an extraction from re-creating it.
     */
    @Test
    void aLiveLegalHoldRefusesTheBundleAndAReleasedOneDoesNot() {
        UUID orgId = seedTenant("Held");
        UUID hold = insertLegalHold(orgId);

        assertThatThrownBy(() -> bundle(orgId, TenantBundler.Mode.REHEARSAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legal")
                .hasMessageContaining("SoftDeletePurgeJob");

        jdbc.update("update platform.legal_hold set released_at = now() where id = ?", hold);
        assertThat(bundle(orgId, TenantBundler.Mode.REHEARSAL).parts()).hasSize(11);
    }

    /**
     * <b>§6 item 11, and the three queues split two ways.</b> {@code notification_delivery} is NOT
     * carried, so anything still queued when the tenant leaves is delivered by nobody.
     * {@code webhook_delivery} and {@code exchange_job} ARE carried, so a PENDING row travels correctly
     * and runs once on the far side — only an IN-FLIGHT one is the problem, because a claimed row in the
     * dump is reclaimed by the far side's stale-lock arm and runs a second time against the tenant's own
     * endpoint. Both halves are asserted, because a rule that refused PENDING rows too would make every
     * bundle impossible to take.
     */
    @Test
    void anUndrainedQueueRefusesTheBundleAndRowsThatTravelCorrectlyDoNot() {
        UUID orgId = seedTenant("Queued");

        UUID pending = insertNotification(orgId, "PENDING");
        assertThatThrownBy(() -> bundle(orgId, TenantBundler.Mode.REHEARSAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivered by nobody");
        jdbc.update("update platform.notification_delivery set status = 'SENT' where id = ?", pending);

        UUID subscription = insertWebhook(orgId, insertPerson("Webhook Owner"));
        insertWebhookDelivery(orgId, subscription, "PENDING");
        assertThat(bundle(orgId, TenantBundler.Mode.REHEARSAL).parts())
                .describedAs("a PENDING webhook travels in the dump and runs once, on the far side")
                .hasSize(11);

        UUID inFlight = insertWebhookDelivery(orgId, subscription, "PROCESSING");
        assertThatThrownBy(() -> bundle(orgId, TenantBundler.Mode.REHEARSAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POSTed twice");
        TenantContext.runAs(orgId, () ->
                jdbc.update("update webhook_delivery set status = 'DELIVERED' where id = ?", inFlight));

        insertExchangeJob(orgId, "PROCESSING");
        assertThatThrownBy(() -> bundle(orgId, TenantBundler.Mode.REHEARSAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resumed on both sides");
    }

    /**
     * The freeze is what {@code TenantExtractionService}'s consistency argument rests on — it says
     * outright that its own answer to "what if somebody writes mid-copy" is that the runbook froze the
     * tenant — and this is the class that can check it. A CUTOVER is the tenant's only copy of itself,
     * so an unfrozen one is a refusal; a REHEARSAL still proves the structure, which is what a rehearsal
     * is for, so it warns and says why it was accepted.
     */
    @Test
    void aCutoverRefusesAnUnfrozenTenantAndARehearsalWarnsInstead() {
        UUID orgId = seedTenant("Frozen");

        assertThatThrownBy(() -> bundle(orgId, TenantBundler.Mode.CUTOVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_freeze")
                .hasMessageContaining("REHEARSAL mode accepts");
        assertThat(bundle(orgId, TenantBundler.Mode.REHEARSAL).warnings())
                .anyMatch(warning -> warning.contains("REHEARSAL"));

        freezes.freeze(orgId, "extraction rehearsal", "TenantBundleTest", Duration.ofMinutes(2));
        try {
            assertThat(bundle(orgId, TenantBundler.Mode.CUTOVER).mode())
                    .isEqualTo(TenantBundler.Mode.CUTOVER);
        } finally {
            freezes.thaw(orgId);
        }
    }

    /**
     * §6 item 8. A rehearsal that killed the tenant's machine credentials is a rehearsal nobody runs
     * twice, so REHEARSAL counts and CUTOVER acts — and what CUTOVER does is two things in one
     * transaction, because revoking is a SOFT delete and {@code uq_api_key_prefix_live} is partial on
     * {@code deleted_at is null}: the prefix goes back into a 48-bit space the moment the key dies. The
     * reservation is what stops that prefix ever meaning another tenant's key on the one lookup that
     * runs before any tenant is known.
     */
    @Test
    void aCutoverRevokesTheOrgsKeysAndReservesTheirPrefixesWhileARehearsalRevokesNothing() {
        UUID orgId = seedTenant("Keyed");
        String prefix = "sk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        insertApiKey(orgId, prefix);

        assertThat(bundle(orgId, TenantBundler.Mode.REHEARSAL).reservedPrefixes()).isEmpty();
        assertThat(liveKeys(orgId)).isEqualTo(1);

        freezes.freeze(orgId, "extraction cutover", "TenantBundleTest", Duration.ofMinutes(2));
        try {
            Collected collected = new Collected();
            TenantBundler.Manifest manifest =
                    bundler.bundle(orgId, TenantBundler.Mode.CUTOVER, collected);

            assertThat(manifest.reservedPrefixes()).containsExactly(prefix);
            assertThat(liveKeys(orgId)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from platform.api_key_prefix_reservation"
                    + " where prefix = ?", Long.class, prefix)).isEqualTo(1);
            assertTheRevocationTravelsWithTheTenant(collected);

            // Re-running a runbook step is what happens after any failure, so the second call must find
            // nothing live, reserve nothing new, and not fail on the prefix it already burned.
            ApiKeyReminting.Reminted again = apiKeys.revokeAndReserve(orgId, "second attempt");
            assertThat(again.revoked()).isZero();
        } finally {
            freezes.thaw(orgId);
        }
    }

    /**
     * <strong>The tenant leaves holding the record that its credentials were retired, and that is a
     * property of the ORDER the bundle is produced in.</strong>
     *
     * <p>{@code ApiKeyRemintingService} records the revocation against the ORG, so
     * {@code AuditLogImpl} routes the row into the tenant's own {@code audit_log} — which is the right
     * table and, until this assertion existed, the wrong moment. Item 8 was emitted LAST, where §6 lists
     * it, so the row was written into a schema item 1 had already finished copying: the extracted
     * deployment arrived with no record of the revocation, and nothing anywhere said so, because the row
     * was perfectly present at the origin in the schema the origin was about to drop. An auditor asking
     * the extracted tenant "when were these keys retired, and by whose instruction" would have found
     * nothing to read.
     *
     * <p>Asserted against the SINK rather than against the database, deliberately: the database holds
     * the row under either ordering, and what is being claimed here is that the row is inside the
     * BUNDLE. The sink is the only place those two facts differ.
     */
    private void assertTheRevocationTravelsWithTheTenant(Collected collected) {
        assertThat(collected.tenant("audit_log"))
                .describedAs("the re-mint's audit row must be in the bundle, not merely in the origin's"
                        + " copy of the tenant — item 8 runs BEFORE item 1 for exactly this reason")
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("action", "apikeys.keys_reminted_for_extraction"));
    }

    /**
     * Both refusals protect the same thing from opposite directions. On a tenant axis the ten
     * platform-side reads would be addressed to a schema that does not hold them; inside a transaction
     * the tenant pin for item 1 is illegal ({@code TenantContext} refuses it) and one snapshot held
     * across a whole tenant pins the oldest xmin cluster-wide and blocks vacuum for every other tenant
     * on the box.
     */
    @Test
    void aBundleRefusesATenantPinnedThreadAndRefusesToRunInsideATransaction() {
        UUID orgId = seedTenant("Axis");

        assertThatThrownBy(() -> TenantContext.runAs(orgId,
                () -> bundler.bundle(orgId, TenantBundler.Mode.REHEARSAL, new Collected())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLATFORM axis");

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                bundler.bundle(orgId, TenantBundler.Mode.REHEARSAL, new Collected())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OUTSIDE a transaction");
    }

    /**
     * <strong>The acceptance test of the platform half.</strong> A second database, built from nothing by
     * the same two migration sequences production runs, receiving the emitted script and nothing else —
     * no Spring context, no seeder, no reachable platform. What it proves is the property §6 item 4 is
     * about: after the restore the tenant's {@code org_subscription} joins to a {@code platform.plan}
     * row, which is the difference between an extracted ENTERPRISE tenant and one silently served the
     * free entitlements.
     *
     * <p>It also proves the ORDER of the script, which is not decorative: {@code external_organization}
     * has the last real foreign key into {@code organization}, so a bundle emitted in the catalogue's
     * alphabetical order aborts on its second platform statement — and, because the script wraps itself
     * in one transaction, takes every tenant row already written down with it.
     *
     * <p>The four infrastructure tables are asserted EMPTY on the far side for the reason §6 gives: a
     * copied {@code shedlock} row is a deployment that silently runs no jobs, and a copied
     * {@code event_publication} row is a webhook delivered twice.
     */
    @Test
    void theScriptRestoresIntoADatabaseThatHasNeverSeenThePlatform() throws Exception {
        UUID orgId = seedTenant("Restored");
        UUID member = insertPerson("Restored Member");
        insertMembership(orgId, member);
        insertWebhook(orgId, member);
        UUID planId = jdbc.queryForObject("select id from platform.plan order by rank limit 1", UUID.class);
        insertSubscription(orgId, planId);
        jdbc.update("insert into platform.shedlock (name, lock_until, locked_at, locked_by)"
                + " values (?, now() + interval '1 hour', now(), 'the platform')", "restore-" + orgId);

        StringWriter script = new StringWriter();
        TenantBundler.Manifest manifest;
        try (BundleScriptWriter writer = new BundleScriptWriter(script)) {
            writer.preamble(orgId, "tenant_pool", TenantBundler.Mode.REHEARSAL);
            manifest = bundler.bundle(orgId, TenantBundler.Mode.REHEARSAL, writer)
                    .withObjectBytes(new TenantBundler.Manifest.ObjectBytes(0, 0));
            manifest.requireComplete();
            writer.epilogue(manifest);
        }

        RestoredDatabase restored = RestoredDatabase.build(script.toString());
        try {
            JdbcTemplate far = restored.jdbc();
            assertThat(far.queryForObject("select count(*) from platform.organization", Long.class))
                    .describedAs("one tenant, its own")
                    .isEqualTo(1);
            assertThat(far.queryForObject(
                    "select name from platform.organization where id = ?", String.class, orgId))
                    .isEqualTo("Restored");
            assertThat(far.queryForObject("""
                    select count(*) from tenant_pool.org_subscription s
                     join platform.plan p on p.id = s.plan_id where s.org_id = ?
                    """, Long.class, orgId))
                    .describedAs("ADR 0010 §6 item 4: the subscription resolves, so the tenant is not"
                            + " silently downgraded to FREE on the far side")
                    .isEqualTo(1);
            assertThat(far.queryForObject(
                    "select secret from tenant_pool.webhook_subscription where org_id = ?",
                    String.class, orgId))
                    .describedAs("the extraction carries what the disclosure bundle redacts")
                    .isEqualTo("RESTORED-SECRET");
            assertThat(far.queryForObject(
                    "select formatted_name from platform.person where id = ?", String.class, member))
                    .isEqualTo("Restored Member");
            assertTheRestoredTenantIsRoutable(far, orgId);
            assertThat(List.of("shedlock", "event_publication", "event_inbox", "idempotency_key",
                    "queue_signal", "tenant_freeze", "api_key", "external_identity", "search_document",
                    "notification_delivery"))
                    .allSatisfy(table -> assertThat(far.queryForObject(
                            "select count(*) from platform." + table, Long.class))
                            .describedAs("platform.%s must arrive empty — fresh, re-minted, rewritten,"
                                    + " rebuilt or drained, and every one of those is a silent failure"
                                    + " when it is a copy instead", table)
                            .isZero());
        } finally {
            restored.drop();
        }
    }

    /**
     * <strong>The restored tenant is ROUTABLE, which every other assertion in this class quietly assumed
     * and none of them tested.</strong> They all address the far side by name — {@code platform.x},
     * {@code tenant_pool.y} — so they would every one of them pass against a deployment that cannot
     * address it at all.
     *
     * <p>{@code TenantRoutes.readHome} answers {@code tenant_pool} for an organization
     * {@code platform.tenant_placement} does not name. On the platform that is the correct answer for an
     * unpromoted tenant; on the far side it is ADR 0010 §1's worst outcome, because the destination ran
     * both migration sequences and therefore HAS a {@code tenant_pool} — empty. A bundle restored into a
     * {@code t_<32hex>} schema without this row serves an organization with no members and no history,
     * and lands its first write in a schema its {@code org_id} disagrees with, and logs nothing at any
     * layer: every one of them is obeying a registry nobody wrote to.
     *
     * <p>Both halves of the assertion are load-bearing. That a row EXISTS catches the omission. That its
     * {@code schema_name} equals the schema the tenant's rows actually landed in — asked of the catalogue
     * rather than compared against {@code "tenant_pool"} spelled here — is what keeps this honest for the
     * restore an operator actually performs: the script's {@code search_path} line is editable precisely
     * so the destination can put its one tenant in a silo instead, and a placement row that did not move
     * with that edit would be the same misroute with an extra step. The statement derives it from
     * {@code current_schema()} for that reason, and this is the assertion that the derivation holds.
     */
    private void assertTheRestoredTenantIsRoutable(JdbcTemplate far, UUID orgId) {
        String whereTheTenantLanded = far.queryForObject("""
                select table_schema from information_schema.tables
                 where table_name = 'membership' and table_schema not in ('information_schema', 'pg_catalog')
                """, String.class);
        assertThat(far.queryForObject("""
                select schema_name || '/' || state from platform.tenant_placement where org_id = ?
                """, String.class, orgId))
                .describedAs("the extracted deployment must be able to ROUTE its one tenant. With no"
                        + " placement row TenantRoutes answers tenant_pool — which exists here, empty,"
                        + " because the migration set built it — so the tenant serves as an organization"
                        + " with no rows and writes into a schema its org_id disagrees with, silently"
                        + " (ADR 0010 §1). The name must be the schema the restore actually landed in,"
                        + " which is what makes the search_path line safe to edit")
                .isEqualTo(whereTheTenantLanded + "/ACTIVE");
        assertThat(far.queryForObject(
                "select count(*) from platform.tenant_placement", Long.class))
                .describedAs("exactly one placement, its own — an extracted deployment that inherited the"
                        + " platform's registry would be leasing schemas it does not have")
                .isEqualTo(1);
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private TenantBundler.Manifest bundle(UUID orgId, TenantBundler.Mode mode) {
        return bundler.bundle(orgId, mode, new Collected());
    }

    /** A sink that keeps what it was handed, which is what a test needs and a runbook never does. */
    private static final class Collected implements TenantBundler.Sink {

        private final Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();

        @Override
        public void row(BundlePart part, String schema, String table, Map<String, Object> row) {
            rows.computeIfAbsent((schema == null ? "" : schema + ".") + table,
                    key -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> platform(String table) {
            return rows.getOrDefault("platform." + table, List.of());
        }

        /**
         * The tenant's own rows — unqualified in the sink because which schema they mean is the
         * destination's business (ADR 0010 §2), which is exactly why they are keyed apart from the
         * platform's copy of a split table of the same name.
         */
        List<Map<String, Object>> tenant(String table) {
            return rows.getOrDefault(table, List.of());
        }

        /** Table names as the sink saw them, unqualified, for "did this travel at all" assertions. */
        List<String> tables() {
            return rows.keySet().stream()
                    .map(key -> key.contains(".") ? key.substring(key.indexOf('.') + 1) : key)
                    .toList();
        }
    }

    private UUID seedTenant(String name) {
        UUID orgId = UUID.randomUUID();
        jdbc.update("""
                insert into platform.organization (id, alias, name, status, version, created_at)
                values (?, ?, ?, 'ACTIVE', 0, now())
                """, orgId, "org-" + orgId.toString().substring(0, 8), name);
        jdbc.update("""
                insert into platform.external_organization (id, organization_id, provider, issuer,
                                                            external_org_id, linked_at, version, created_at)
                values (?, ?, 'keycloak', 'https://issuer.test', ?, now(), 0, now())
                """, UUID.randomUUID(), orgId, orgId.toString());
        return orgId;
    }

    private UUID insertPerson(String name) {
        UUID personId = UUID.randomUUID();
        jdbc.update("""
                insert into platform.person (id, status, invited_at, formatted_name, given_name,
                                             family_name, version, created_at)
                values (?, 'ACTIVE', now(), ?, 'Given', 'Family', 0, now())
                """, personId, name);
        return personId;
    }

    private void insertVerifiedContact(UUID personId, String value) {
        jdbc.update("""
                insert into platform.person_contact (id, person_id, kind, contact_value, is_primary,
                                                     verified_at, version, created_at)
                values (?, ?, 'EMAIL', ?, true, now(), 0, now())
                """, UUID.randomUUID(), personId, value);
    }

    private void insertSession(UUID orgId, UUID actor, UUID target, String reason) {
        jdbc.update("""
                insert into platform.impersonation_session (id, actor_person_id, target_person_id, org_id,
                                                            reason, mode, started_at, expires_at,
                                                            version, created_at)
                values (?, ?, ?, ?, ?, 'READ_ONLY', now(), now() + interval '15 minutes', 0, now())
                """, UUID.randomUUID(), actor, target, orgId, reason);
    }

    private void insertMembership(UUID orgId, UUID personId) {
        UUID roleId = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> {
            jdbc.update("""
                    insert into org_role (id, org_id, code, name, system_role, version, created_at)
                    values (?, ?, 'OWNER', 'Owner', true, 0, now())
                    """, roleId, orgId);
            jdbc.update("""
                    insert into membership (id, org_id, person_id, role_id, status, version, created_at)
                    values (?, ?, ?, ?, 'ACTIVE', 0, now())
                    """, UUID.randomUUID(), orgId, personId, roleId);
        });
    }

    /** Returns the subscription id, and names {@code author} as the row's {@code created_by}. */
    private UUID insertWebhook(UUID orgId, UUID author) {
        UUID subscriptionId = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into webhook_subscription (id, org_id, url, secret, event_types, status,
                                                  version, created_at, created_by)
                values (?, ?, 'https://hook.test/x', 'RESTORED-SECRET', 'org.member.added', 'ACTIVE',
                        0, now(), ?)
                """, subscriptionId, orgId, author));
        return subscriptionId;
    }

    private UUID insertWebhookDelivery(UUID orgId, UUID subscriptionId, String status) {
        UUID id = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into webhook_delivery (id, subscription_id, org_id, event_type, payload, status,
                                              attempts, max_attempts, next_attempt_at, created_at)
                values (?, ?, ?, 'org.member.added', '{}', ?, 0, 5, now(), now())
                """, id, subscriptionId, orgId, status));
        return id;
    }

    private void insertExchangeJob(UUID orgId, String status) {
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into exchange_job (id, org_id, requester_person_id, job_type, handler, format,
                                          status, created_at)
                values (?, ?, ?, 'EXPORT', 'members', 'CSV', ?, now())
                """, UUID.randomUUID(), orgId, UUID.randomUUID(), status));
    }

    private void insertSubscription(UUID orgId, UUID planId) {
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into org_subscription (id, org_id, plan_id, status, version, created_at)
                values (?, ?, ?, 'ACTIVE', 0, now())
                """, UUID.randomUUID(), orgId, planId));
    }

    private UUID insertNotification(UUID orgId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into platform.notification_delivery (id, channel, recipient, subject, status,
                                                            attempts, max_attempts, next_attempt_at,
                                                            created_at, org_id)
                values (?, 'EMAIL', 'someone@example.test', 'hello', ?, 0, 5, now(), now(), ?)
                """, id, status, orgId);
        return id;
    }

    private UUID insertLegalHold(UUID orgId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into platform.legal_hold (id, scope, org_id, reason, placed_by_person_id, placed_at)
                values (?, 'ORG', ?, 'litigation', ?, now())
                """, id, orgId, UUID.randomUUID());
        return id;
    }

    private void insertApiKey(UUID orgId, String prefix) {
        jdbc.update("""
                insert into platform.api_key (id, org_id, name, prefix, secret_hash, version, created_at)
                values (?, ?, 'machine', ?, repeat('a', 64), 0, now())
                """, UUID.randomUUID(), orgId, prefix);
    }

    private long liveKeys(UUID orgId) {
        Long live = jdbc.queryForObject(
                "select count(*) from platform.api_key where org_id = ? and deleted_at is null",
                Long.class, orgId);
        return live == null ? 0 : live;
    }
}
