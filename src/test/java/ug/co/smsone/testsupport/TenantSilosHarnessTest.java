package ug.co.smsone.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.persistence.TenantMigrationRunner;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.placement.PlacementState;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;

/**
 * The mixed-tenancy fixture, proved (ADR 0010 §7 Phase 5).
 *
 * <p>A fixture that quietly does not work is worse than no fixture, because everything built on it goes
 * green for the wrong reason — and this one's whole purpose is to stop the suite being green for the
 * wrong reason. So both halves are asserted here: that {@link TenantSilos#place} really builds a second
 * schema through the real migration sequence and really records it, and that the cleanup really runs
 * between tests without any caller remembering to ask for it.
 *
 * <p><b>Nothing here asserts on ROUTING.</b> That {@code TenantSchemas.searchPathFor} sends a placed
 * tenant to its own schema is the router's claim and it is asserted in
 * {@code SiloTenantProvisioningTest} and in the fan-out gates. This class owns the state, not the road
 * to it — otherwise a change to the router would mean editing two tests that say the same thing.
 *
 * <p>The methods are explicitly ordered, and that is an assertion rather than tidiness:
 * {@link #noSiloSurvivesTheTestThatMadeIt()} only means anything if it runs immediately after the test
 * that made one. Its subject is {@link TenantSilos#afterEach}, which is not otherwise observable from
 * inside a test method.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantSilosHarnessTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TenantPlacements placements;

    private final List<UUID> invented = new ArrayList<>();

    /**
     * Placement rows this class wrote that do NOT name a silo — the extension's sweep is keyed on the
     * silo schema name, so an ACTIVE {@code tenant_pool} row written to set up a refusal is the one
     * thing it cannot see. Same mechanism, and for the same reason, as
     * {@code TenantPlacementsRegistryTest}'s.
     */
    @AfterEach
    void forgetThePooledPlacementsThisTestWrote() {
        invented.forEach(orgId ->
                jdbc.update("delete from platform.tenant_placement where org_id = ?", orgId));
        invented.clear();
    }

    /**
     * The headline claim, asserted as a fact about the whole database rather than about one row: after
     * this call there is <em>exactly one</em> silo schema and <em>exactly one</em> tenant placed in one,
     * and every other organization is where it was.
     *
     * <p>The two {@code to_regclass} probes are what prove the schema went through the real tenant
     * sequence rather than merely being created: {@code membership} is tenant-tier so it must be there,
     * and {@code organization} is platform-tier so it must not. A silo carrying {@code organization}
     * would mean the platform sequence had been replayed into a tenant, which is the mistake ADR 0010
     * §2 exists to prevent and which nothing at runtime would notice.
     */
    @Test
    @Order(1)
    void placingAnOrgGivesItItsOwnSchemaAtThePoolsHeadWhileEveryOtherOrgStaysPooled() {
        UUID siloed = org();
        UUID pooled = org();

        String silo = silos.place(siloed);

        assertThat(silo).isEqualTo(TenantSchemas.siloSchema(siloed));
        assertThat(siloSchemasInTheCatalogue())
                .as("one org in a silo and the rest pooled — the fixture's entire contract")
                .containsExactly(silo);
        assertThat(head(silo))
                .as("a silo behind the pool is what TenantSchemaHeadParityTest fails on, and what the"
                        + " §4.4 floor check answers 503 for")
                .isEqualTo(head(TenantSchemas.TENANT_POOL));
        assertThat(relationExists(silo, "membership"))
                .as("the real tenant sequence ran here, not just `create schema`")
                .isTrue();
        assertThat(relationExists(silo, "organization"))
                .as("and only the tenant sequence — a platform-tier table in a silo is a boundary"
                        + " violation nothing at runtime would notice (ADR 0010 §2)")
                .isFalse();

        TenantPlacement placement = placements.find(siloed).orElseThrow();
        assertThat(placement.schemaName()).isEqualTo(silo);
        assertThat(placement.state())
                .as("ACTIVE is what licenses serving the tenant at all")
                .isEqualTo(PlacementState.ACTIVE);
        assertThat(placement.schemaVersion())
                .as("the registry's version is the schema's real head, which is what the floor reads")
                .isEqualTo(head(silo));
        assertThat(placement.dataSourceName()).isEqualTo(TenantPlacement.PRIMARY_DATASOURCE);

        assertThat(placements.find(pooled))
                .as("placing one tenant must not write a row for any other")
                .isEmpty();
    }

    /**
     * The cleanup, which is the half a caller cannot be trusted with. Nothing in the previous test asked
     * for a drop and nothing in this one arranges the state it asserts — the silo built one method ago
     * is simply gone, because {@link TenantSilos#afterEach} ran between them.
     *
     * <p>A leak would not fail here in any useful way if this class were the only reader; it would fail
     * in {@code TenancyTierBoundaryTest} and {@code TenantSchemaHeadParityTest}, naming a schema whose
     * origin nothing connects to the test that made it. That is the failure this assertion exists to
     * turn into a local one.
     */
    @Test
    @Order(2)
    void noSiloSurvivesTheTestThatMadeIt() {
        assertThat(siloSchemasInTheCatalogue())
                .as("the previous test placed a tenant in a silo and never dropped it — the extension did")
                .isEmpty();
        assertThat(placementsNamingASilo())
                .as("and the registry must not go on naming a schema that is gone: the migration runner"
                        + " reports a placement with no schema as a broken tenant")
                .isEmpty();
    }

    /**
     * Moving a tenant that is already serving is PROMOTION — a freeze window, a row copy, verified
     * counts, a cache eviction (ADR 0010 §6 hop 0→1) — and this fixture copies nothing. It refuses
     * through the registry's own condition rather than through a second copy of the rule: {@code
     * reserve} declines an ACTIVE row, and that decline is the guard.
     */
    @Test
    @Order(3)
    void placingATenantThatIsAlreadyServingIsRefusedBecauseThatMoveIsPromotion() {
        UUID serving = org();
        placements.announce(serving, TenantSchemas.TENANT_POOL);

        assertThatThrownBy(() -> silos.place(serving))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROMOTION");

        assertThat(placements.find(serving).orElseThrow().schemaName())
                .as("a refused placement must leave the tenant exactly where it was")
                .isEqualTo(TenantSchemas.TENANT_POOL);
        assertThat(siloSchemasInTheCatalogue())
                .as("and must not have built a schema on the way to refusing")
                .isEmpty();
    }

    /**
     * The wrong-order mistake, refused loudly. Seed the organization's tenant-tier rows first and those
     * rows are in {@code tenant_pool}; place it afterwards and every later read goes to an empty silo.
     * Nothing throws at the point of the mistake, so the fixture throws at the point that can still name
     * it.
     */
    @Test
    @Order(4)
    void placingAnOrgThatAlreadyHasPooledRowsIsRefusedBecauseNothingIsCopied() {
        UUID orgId = org();
        UUID personId = EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
        EdgeSeed.member(jdbc, orgId, personId, "ADMIN");

        assertThatThrownBy(() -> silos.place(orgId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("copies nothing");

        assertThat(placements.find(orgId))
                .as("the refusal comes before the reserve, so a refused org holds no placement at all")
                .isEmpty();
    }

    /**
     * The sweep on its own, because two other classes call it directly and one of them has no Spring
     * context at all. It must remove the silo and its registry row and touch nothing else — a sweep that
     * took {@code tenant_pool} with it would take the next 89 database-touching test classes with it too.
     */
    @Test
    @Order(5)
    void theSweepDropsTheSiloForgetsItsPlacementAndLeavesThePoolStanding() {
        UUID siloed = org();
        String silo = silos.place(siloed);
        assertThat(siloSchemasInTheCatalogue()).containsExactly(silo);

        TenantSilos.dropEverySilo(dataSource);

        assertThat(siloSchemasInTheCatalogue()).isEmpty();
        assertThat(placements.find(siloed)).isEmpty();
        assertThat(relationExists(TenantSchemas.TENANT_POOL, "membership"))
                .as("the pool every other tenant lives in is not the sweep's business")
                .isTrue();
        assertThat(head(TenantSchemas.TENANT_POOL)).isNotNull();
    }

    /**
     * An organization row with the provider link a token resolves through, and nothing tenant-tier —
     * which is exactly the state {@link TenantSilos#place} requires. Its id is registered so a placement
     * row naming {@code tenant_pool} does not outlive the test.
     */
    private UUID org() {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "ext-" + UUID.randomUUID());
        invented.add(orgId);
        return orgId;
    }

    private List<String> siloSchemasInTheCatalogue() {
        return jdbc.queryForList(
                "select schema_name from information_schema.schemata"
                        + " where schema_name ~ '^t_[0-9a-f]{32}$' order by 1",
                String.class);
    }

    private List<String> placementsNamingASilo() {
        return jdbc.queryForList(
                "select schema_name from platform.tenant_placement"
                        + " where schema_name ~ '^t_[0-9a-f]{32}$' order by 1",
                String.class);
    }

    /** The version a schema is actually at, read the way the runner and the parity test read it. */
    private String head(String schema) {
        return jdbc.execute((ConnectionCallback<String>) connection ->
                TenantMigrationRunner.historyHead(connection, schema));
    }

    private boolean relationExists(String schema, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass(cast(? as text)) is not null", Boolean.class, schema + "." + table));
    }
}
