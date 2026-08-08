package ug.co.smsone.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.placement.PlacementState;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.shared.tenancy.promotion.TenantFreezes;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * Where the fleet lives, and which homes a background sweep is allowed to touch (ADR 0010 §3.4, §5,
 * §6 hop 0→1, Phase 5).
 *
 * <p>Every fanned-out job in the codebase takes its list of schemas from here, so a mistake in this
 * class is a mistake in all of them at once — and in the silent direction: a home missing from
 * {@link TenantFanOut.Fleet#homes()} is a tenant whose retention, escalation and billing stop happening
 * with no exception raised anywhere, and a home that should have been withheld is a sweep writing into
 * a schema a promoter is copying.
 */
class TenantFanOutTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private TenantFanOut fanOut;

    @Autowired
    private TenantPlacements placements;

    @Autowired
    private TenantFreezes freezes;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> invented = new ArrayList<>();

    /**
     * One Postgres container serves every test class and {@link TenantSilos} only sweeps rows naming a
     * silo SCHEMA. A freeze or a PROVISIONING placement left behind here would stand the pool down for
     * whichever class ran next — which would fail as "the nightly purge did nothing", three files away.
     */
    @AfterEach
    void forgetTheTenantsThisTestInvented() {
        invented.forEach(orgId -> {
            freezes.thaw(orgId);
            jdbc.update("delete from platform.tenant_placement where org_id = ?", orgId);
        });
        invented.clear();
    }

    @Test
    void theFleetIsThePoolFirstAndThenEveryActiveSilo() {
        UUID siloed = organization();
        String schema = silos.place(siloed);

        List<TenantHome> homes = fanOut.fleet().homes();

        assertThat(homes.getFirst().pooled())
                .as("the pool is always first — it holds every tenant nobody has promoted, so a "
                        + "rotation that could leave it until last would starve the fleet to serve the "
                        + "exception")
                .isTrue();
        assertThat(homes).extracting(TenantHome::schema)
                .containsExactly(TenantSchemas.TENANT_POOL, schema);
        assertThat(homes.get(1).axis())
                .as("a silo's axis is the organization it is named after, which is what lets a sweep pin "
                        + "it with TenantContext.runAs and leave the schema to the router")
                .isEqualTo(siloed);
    }

    @Test
    void aPooledTenantNeedsNoPlacementRowAndStillHasAHome() {
        UUID pooled = organization();

        assertThat(fanOut.fleet().homeOf(pooled))
                .as("an organization with no placement row at all is pooled and serving — that is the "
                        + "shipped case (ADR 0010 §4.3), so absence must resolve to the pool and not to "
                        + "a refusal")
                .contains(TenantHome.pool());
    }

    @Test
    void aSiloedTenantResolvesToItsOwnHomeAndNotToThePool() {
        UUID siloed = organization();
        String schema = silos.place(siloed);

        assertThat(fanOut.fleet().homeOf(siloed)).map(TenantHome::schema).contains(schema);
    }

    /**
     * The promotion freeze reaching background work, which is the half {@code maintenance_window} cannot
     * gate (ADR 0010 §6 hop 0→1). A tenant being promoted is frozen while its rows are still in
     * {@code tenant_pool}, so the whole schema has to stand down: the fan-out's unit is a schema and the
     * freeze's is a tenant, and there is no way to say "every org but this one" to a pool-wide sweep.
     */
    @Test
    void aFrozenTenantThatIsStillPooledStandsTheWholePoolDown() {
        UUID promoting = organization();
        freeze(promoting);

        TenantFanOut.Fleet fleet = fanOut.fleet();

        assertThat(fleet.poolPaused()).isTrue();
        assertThat(fleet.homes())
                .as("no sweep may touch tenant_pool while a tenant is being copied out of it")
                .noneMatch(TenantHome::pooled);
        assertThat(fleet.homeOf(promoting)).isEmpty();
        assertThat(fleet.homeOf(organization()))
                .as("and neither may it touch anybody else's rows in that schema — that is the price of "
                        + "the granularity, and it is affordable only because every consumer resumes")
                .isEmpty();
    }

    /**
     * The other side of the same rule. Once the placement has been flipped, the frozen tenant has a
     * schema of its own, so withholding the whole pool would pause the entire fleet for one tenant's
     * unthawed freeze.
     */
    @Test
    void aFrozenTenantThatIsAlreadySiloedLosesOnlyItsOwnHome() {
        UUID siloed = organization();
        String schema = silos.place(siloed);
        freeze(siloed);

        TenantFanOut.Fleet fleet = fanOut.fleet();

        assertThat(fleet.poolPaused())
                .as("nothing else lives in that tenant's schema, so nothing else needs to stand down")
                .isFalse();
        assertThat(fleet.homes()).extracting(TenantHome::schema)
                .contains(TenantSchemas.TENANT_POOL)
                .doesNotContain(schema);
        assertThat(fleet.homeOf(siloed))
                .as("asked directly, a frozen tenant has no home this pass — it is still in activeSilos() "
                        + "because the freeze outlives the flip until the promoter thaws it")
                .isEmpty();
    }

    /**
     * An expired freeze is not a freeze. A promoter that is killed between {@code freeze} and
     * {@code thaw} must not stop a tenant's retention forever, so the deadline is what bounds the
     * outage — and the fan-out has to honour that or the bound means nothing.
     */
    @Test
    void anExpiredFreezeDoesNotHoldThePoolDown() {
        UUID promoting = organization();
        invented.add(promoting);
        freezes.freeze(promoting, "promotion that died", "test", Duration.ofMinutes(30));
        // THE WHOLE WINDOW MOVES, not just its end. V58's `tenant_freeze_ends_after_it_starts` refuses
        // a row whose expires_at precedes its frozen_at, and it is right to: that row reads as "not
        // frozen" to every consumer, so a promotion could copy rows with nothing paused at all. Pushing
        // only expires_at into the past therefore does not simulate a lapsed freeze — it asks the
        // database to accept a state a real one can never be in. What a promoter killed half an hour ago
        // actually leaves is a full window that has run out, which is what this writes.
        jdbc.update("""
                update platform.tenant_freeze
                   set frozen_at = now() - interval '40 minutes',
                       expires_at = now() - interval '10 minutes'
                 where org_id = ?
                """, promoting);
        assertThat(freezes.isFrozen(promoting))
                .as("the row is still there — it is its deadline that has passed, which is the whole "
                        + "difference between an expired freeze and a thawed one")
                .isFalse();

        assertThat(fanOut.fleet().poolPaused())
                .as("a freeze nobody released must lapse, or one dead promoter stops every tenant's "
                        + "background work indefinitely")
                .isFalse();
    }

    /**
     * A relocation recorded as in-flight stands the pool down on its own. {@code TenantPromoter} takes
     * the freeze and then calls {@code beginRelocation}, so this covers the window between the two and
     * the case where a freeze lapsed under a copy that is still running.
     */
    @Test
    void aRelocationRecordedInTheRegistryAlsoStandsThePoolDown() {
        UUID promoting = organization();
        invented.add(promoting);
        assertThat(placements.announce(promoting, TenantSchemas.TENANT_POOL)).isTrue();
        assertThat(placements.beginRelocation(promoting, TenantSchemas.TENANT_POOL)).isTrue();

        assertThat(fanOut.fleet().poolPaused())
                .as("the registry's own record of an in-flight move is the second reader, and it is "
                        + "durable where the freeze is bounded")
                .isTrue();
    }

    /**
     * A half-built silo is not a home. Including it would have a sweep purging, escalating or billing
     * from a schema nothing has proved fit — and, when the provision was a promotion, from one whose
     * copy is still running.
     */
    @Test
    void aSiloThatIsNotActiveIsNotAHomeToSweep() {
        UUID provisioning = UUID.randomUUID();
        invented.add(provisioning);
        placements.reserve(provisioning, TenantSchemas.siloSchema(provisioning));

        assertThat(fanOut.fleet().homes()).extracting(TenantHome::schema)
                .doesNotContain(TenantSchemas.siloSchema(provisioning));
    }

    /**
     * <b>The signup that is in flight right now, which under {@code silo-per-org} is the common case and
     * not an exotic one.</b> Its placement names a silo that is still being built, so the honest answer
     * is "not this pass" — and the answer that must never be given is {@code tenant_pool}, because
     * {@code TenantRoutes} is already routing that tenant to {@code t_<hex>} and the two disagreeing
     * about a live tenant is ADR 0010 §1's worst failure reached from the background side.
     */
    @Test
    void aTenantWhoseSiloIsStillBeingBuiltHasNoHomeThisPassAndIsNeverSentToThePool() {
        UUID provisioning = organization();
        placements.reserve(provisioning, TenantSchemas.siloSchema(provisioning));

        assertThat(fanOut.fleet().homeOf(provisioning))
                .as("PROVISIONING means this tenant's home is not settled — a sweep that fell through to "
                        + "the pool would do its work in a schema the tenant does not live in")
                .isEmpty();
    }

    /**
     * <b>A serving siloed tenant one failed migration pass old.</b> This is not a hypothetical state:
     * {@code TenantMigrationRunner}'s FAIL_PLACEMENT is keyed by {@code schema_name} and spares no
     * serving row — its one exemption ({@code NOT_A_PROVISIONING_FAILURE}) is a FAILED that carries no
     * {@code schema_version}, which is a tenant whose schema was never built and never announced, not
     * this one. So a pass that fails over {@code t_<hex>} marks that tenant FAILED while it is ACTIVE,
     * serving, and holding every one of its rows in that schema.
     *
     * <p>{@code activeSilos()} already refuses to SWEEP such a home ("nothing has proved it fit"), so
     * answering {@code tenant_pool} for the same tenant one method later undoes that refusal in the worst
     * available way — {@code UsageExportJob} would look for its billing account in the pool, find none,
     * and file the tenant as unbillable every night until somebody noticed the money.
     */
    @Test
    void aSiloedTenantWhoseMigrationFailedIsRefusedRatherThanAnsweredWithThePool() {
        UUID siloed = organization();
        String schema = silos.place(siloed);
        // The runner's own statement, verbatim in shape: keyed by SCHEMA, sparing no serving row. Its
        // one exemption does not apply here — silos.place() recorded a schema_version, so this row is
        // not the never-built shape the runner leaves alone. Naming a silo it addresses exactly one
        // tenant, which is why this is safe to run against the shared container while the real one over
        // `tenant_pool` would not be.
        jdbc.update("""
                update platform.tenant_placement
                   set state = 'FAILED', last_error = 'V59 failed', updated_at = now()
                 where schema_name = ?
                """, schema);

        TenantFanOut.Fleet fleet = fanOut.fleet();

        assertThat(fleet.homeOf(siloed))
                .as("its rows are in %s and TenantRoutes still routes it there — the pool is the one "
                        + "answer that is certainly wrong", schema)
                .isEmpty();
        assertThat(fleet.homes()).extracting(TenantHome::schema)
                .as("and the home itself is still withheld, which is the half that already worked")
                .doesNotContain(schema);
    }

    /**
     * The other side of that split, and it is the one that keeps the refusal affordable. A FAILED row
     * naming {@code tenant_pool} still resolves to the pool: those rows really are there, the router
     * agrees, and the same FAIL_PLACEMENT statement marks EVERY pooled tenant FAILED at once when a pass
     * over {@code tenant_pool} fails — so refusing them would stand the whole fleet's per-tenant work
     * down over one bad migration, which is precisely what ADR 0010 §8 Q7 rules out.
     */
    @Test
    void aPooledTenantWhoseMigrationFailedKeepsThePoolAsItsHome() {
        UUID pooled = organization();
        placements.reserve(pooled, TenantSchemas.TENANT_POOL);
        placements.markFailed(pooled, "migration of tenant_pool failed");

        TenantFanOut.Fleet fleet = fanOut.fleet();

        assertThat(fleet.homeOf(pooled))
                .as("a broken migration must not also stop this tenant's retention, escalation and "
                        + "billing — serve it and page a human")
                .contains(TenantHome.pool());
        assertThat(fleet.poolPaused())
                .as("and FAILED pauses nothing, or one bad pass over the pool pauses the fleet")
                .isFalse();
    }

    /**
     * <b>A tenant recorded ACTIVE in a schema nobody ever built, which is the one shape that costs every
     * OTHER home its run.</b> {@link PlacementState#ACTIVE} means three things at once — the schema
     * exists, it is at {@code schema_version}, and the tenant has been announced — and exactly one write
     * can produce a row asserting the first and third without the second: {@code announce}'s INSERT arm
     * reads the version from the tenants already living in that schema, and a fresh silo has none. Under
     * {@code silo-per-org} that is what an announcement running without the provisioning that must
     * precede it (ADR 0010 §4.3) leaves behind, and it is exactly what a bypassed
     * {@code TenantProvisioner} produced in the run this test was written from.
     *
     * <p>Both assertions are the point, and they fail in different directions. Swept, that schema is
     * absent from the {@code search_path} Postgres accepts without complaint, so the visit dies on its
     * first unqualified table with {@code relation "org_group" does not exist} — and the fan-out's
     * loud-AND-complete rule then fails the nightly purge, the trial expiry, the dunning pass and the SLA
     * escalation, every run, naming a schema belonging to a tenant nobody asked about. Answered with
     * {@code tenant_pool} instead, it would be the opposite mistake: {@code TenantRoutes} routes that
     * tenant to {@code t_<hex>}, so the pool is the one answer that is certainly wrong.
     */
    @Test
    void aSiloWithNoRecordedVersionIsNoHomeAtAllAndDoesNotCostTheOtherHomesTheirRun() {
        UUID unbuilt = organization();
        String schema = TenantSchemas.siloSchema(unbuilt);
        // The announcement with no provisioning behind it: no reserve, no CREATE SCHEMA, no Flyway pass,
        // so nothing has ever recorded a version against that name.
        TenantContext.runAsPlatform(() -> placements.announce(unbuilt, schema));
        TenantPlacement announced =
                TenantContext.callAsPlatform(() -> placements.find(unbuilt)).orElseThrow();
        assertThat(announced.state())
                .as("the fixture has to produce the state under test, not merely a broken tenant")
                .isEqualTo(PlacementState.ACTIVE);
        assertThat(announced.schemaVersion())
                .as("and the missing version is what makes it a home nobody built")
                .isNull();

        TenantFanOut.Fleet fleet = fanOut.fleet();

        assertThat(fleet.homes()).extracting(TenantHome::schema)
                .as("a schema nothing has migrated is not a home; visiting it fails the whole run's "
                        + "report for every tenant that does have one")
                .doesNotContain(schema);
        assertThat(fleet.homeOf(unbuilt))
                .as("and the pool is not a fallback for it — the router sends that tenant to %s, so "
                        + "sweeping it in tenant_pool would be the misroute stated the other way round",
                        schema)
                .isEmpty();
        assertThat(fleet.homes()).extracting(TenantHome::schema)
                .as("one broken registry row must not take the rest of the fleet's work with it")
                .contains(TenantSchemas.TENANT_POOL);
    }

    private UUID organization() {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "fanout-" + UUID.randomUUID());
        invented.add(orgId);
        return orgId;
    }

    private void freeze(UUID orgId) {
        freezes.freeze(orgId, "promotion of " + orgId, "TenantFanOutTest", Duration.ofMinutes(5));
    }
}
