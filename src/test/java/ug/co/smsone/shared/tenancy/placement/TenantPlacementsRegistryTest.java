package ug.co.smsone.shared.tenancy.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The registry's four conditional writes, against real Postgres (ADR 0010 §4.2).
 *
 * <p>Every one of them is an upsert whose {@code ON CONFLICT … WHERE} clause is the actual logic, so
 * there is nothing here a unit test could reach: what is under test is what Postgres does with two
 * writers and one row, not what a method returns. That is also why none of these tests reads the row
 * back before acting on it — the whole point of the shapes below is that the question and the claim
 * are one statement.
 *
 * <p>The org ids are random and belong to no {@code organization} row, deliberately: {@code org_id} is
 * a soft ref with no foreign key (V57 gives three reasons), and a test that had to create a tenant to
 * exercise the registry would be testing the tenant path instead.
 */
class TenantPlacementsRegistryTest extends AbstractIntegrationTest {

    @Autowired
    private TenantPlacements placements;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> written = new ArrayList<>();

    /**
     * <strong>Not optional housekeeping.</strong> The rows below name silo schemas that were never
     * created, and {@code TenantMigrationRunner.discoverFleet()} takes the fleet from
     * {@code select distinct schema_name from platform.tenant_placement} — so a leftover row here is a
     * placement pointing at nothing, which that runner correctly reports as a failed schema. Leaving
     * them would fail the migration runner's tests, in another class, for a reason that would look
     * nothing like this one.
     */
    @AfterEach
    void forgetTheTenantsThisTestInvented() {
        written.forEach(orgId ->
                jdbc.update("delete from platform.tenant_placement where org_id = ?", orgId));
        written.clear();
    }

    /**
     * The announcement gate. {@code OrganizationRegistered} fans out to a trial, a billing account and
     * a search document, so "announce this tenant" must be answerable exactly once even when two
     * retries of the same signup ask at the same instant — which is why the answer is a row count and
     * not a read followed by a write.
     */
    @Test
    void announceIsTrueExactlyOncePerTenantAndTheRowSaysWhereTheTenantLives() {
        UUID orgId = tenant();

        assertThat(placements.announce(orgId, "tenant_pool"))
                .as("the first call is the one that gets to publish OrganizationRegistered")
                .isTrue();
        assertThat(placements.announce(orgId, "tenant_pool"))
                .as("a second call must publish nothing — the tenant is already announced")
                .isFalse();

        TenantPlacement placement = placements.find(orgId).orElseThrow();
        assertThat(placement.state()).isEqualTo(PlacementState.ACTIVE);
        assertThat(placement.schemaName()).isEqualTo("tenant_pool");
        assertThat(placement.dataSourceName()).isEqualTo(TenantPlacement.PRIMARY_DATASOURCE);
        assertThat(placement.isActive()).isTrue();
    }

    /**
     * Moving a tenant that is already serving is PROMOTION — a freeze window, a copy, verified row
     * counts, a cache eviction (ADR 0010 §6, Phase 5). Flipping a config flag and re-running a signup
     * is not promotion, and this is the clause that says so: a reserve against a serving tenant must
     * change nothing at all, not even its schema name.
     */
    @Test
    void reserveClaimsAHomeForAnUnplacedTenantAndDeclinesOneThatIsAlreadyServing() {
        UUID unplaced = tenant();
        assertThat(placements.reserve(unplaced, "t_" + hex())).isTrue();
        assertThat(placements.find(unplaced).orElseThrow().state()).isEqualTo(PlacementState.PROVISIONING);

        UUID serving = tenant();
        placements.announce(serving, "tenant_pool");

        assertThat(placements.reserve(serving, "t_" + hex()))
                .as("a serving tenant is moved by promotion, never by a reserve")
                .isFalse();
        TenantPlacement untouched = placements.find(serving).orElseThrow();
        assertThat(untouched.state()).isEqualTo(PlacementState.ACTIVE);
        assertThat(untouched.schemaName()).isEqualTo("tenant_pool");
    }

    /**
     * The reason the registry exists rather than a log line: after a failure, "which tenants are not
     * fit to serve, and why" is a {@code select} (ADR 0010 §4.2). And the same asymmetry as above — a
     * tenant that is already serving does not become unserveable because some later attempt to move it
     * failed.
     */
    @Test
    void aFailedPlacementIsQueryableWithItsReasonAndNeverUnseatsAServingTenant() {
        UUID broken = tenant();
        placements.reserve(broken, "t_" + hex());
        placements.markFailed(broken, "relation \"org_role\" already exists");

        TenantPlacement placement = placements.find(broken).orElseThrow();
        assertThat(placement.state()).isEqualTo(PlacementState.FAILED);
        assertThat(placement.lastError()).contains("org_role");
        assertThat(placement.schemaVersion()).as("nothing was applied, so no version was reached").isNull();
        assertThat(placements.findByState(PlacementState.FAILED))
                .extracting(TenantPlacement::orgId)
                .contains(broken);

        UUID serving = tenant();
        placements.announce(serving, "tenant_pool");
        placements.markFailed(serving, "a later attempt to move this tenant failed");
        assertThat(placements.find(serving).orElseThrow().state()).isEqualTo(PlacementState.ACTIVE);

        // Retrying re-claims the same row rather than creating a second tenant, and clears the reason
        // so a stale one cannot be read as a current one.
        assertThat(placements.reserve(broken, "t_" + hex())).isTrue();
        assertThat(placements.find(broken).orElseThrow().lastError()).isNull();
    }

    /**
     * The version is a property of the SCHEMA, denormalized onto every tenant that lives in it — so
     * moving five thousand pooled tenants is one statement, not five thousand. This is the shape
     * Phase 4's fleet runner writes through, and the reason §4.4's floor check can be a single-row
     * lookup on the request path instead of a join.
     *
     * <p>It must not touch {@code state}: a schema reaching head says nothing about whether the
     * tenants in it have been announced, and conflating the two would announce a tenant as a side
     * effect of a migration run.
     */
    @Test
    void recordMigratedIsKeyedByTheSchemaSoOneStatementMovesEveryTenantInIt() {
        String schema = "t_" + hex();
        UUID first = tenant();
        UUID second = tenant();
        UUID elsewhere = tenant();
        placements.reserve(first, schema);
        placements.announce(second, schema);
        placements.announce(elsewhere, "tenant_pool");

        // Deliberately not a version any migration can carry. The control tenant below lives in the
        // REAL tenant_pool, and announce() inherits a schema's already-recorded version from its
        // siblings — so the pool's row already holds whatever the tenant sequence's head is. An
        // arbitrary-looking literal collided with exactly that (head was V53), and "untouched" then
        // could not tell untouched from touched. The version this test writes must be one no schema
        // can legitimately reach.
        String applied = "9000.1";
        // What the control holds BEFORE the call is the only correct thing to compare against: it
        // makes the assertion about preservation rather than about a literal that can drift into
        // agreement with the fleet's real head on some later migration.
        String elsewhereBefore = placements.find(elsewhere).orElseThrow().schemaVersion();

        assertThat(placements.recordMigrated(schema, applied)).isEqualTo(2);

        assertThat(placements.find(first).orElseThrow().schemaVersion()).isEqualTo(applied);
        assertThat(placements.find(first).orElseThrow().state())
                .as("a migration run must not announce a tenant")
                .isEqualTo(PlacementState.PROVISIONING);
        assertThat(placements.find(second).orElseThrow().schemaVersion()).isEqualTo(applied);
        assertThat(placements.find(elsewhere).orElseThrow().schemaVersion())
                .as("a tenant in another schema is untouched")
                .isEqualTo(elsewhereBefore);
        assertThat(placements.schemas()).contains(schema, "tenant_pool");
    }

    /**
     * A tenant key nothing else will ever use, registered for removal. Random and in no
     * {@code organization} row on purpose: {@code org_id} is a soft ref with no foreign key (V57 gives
     * three reasons), so a test that had to create a tenant to exercise the registry would be testing
     * the tenant path instead.
     */
    private UUID tenant() {
        UUID orgId = UUID.randomUUID();
        written.add(orgId);
        return orgId;
    }

    /** A silo-shaped name for a schema that is never created — the registry stores names, DDL is elsewhere. */
    private static String hex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
