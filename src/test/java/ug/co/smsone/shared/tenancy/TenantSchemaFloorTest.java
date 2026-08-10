package ug.co.smsone.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ug.co.smsone.shared.tenancy.TenantSchemaFloor.MIN_TENANT_SCHEMA_VERSION;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import ug.co.smsone.shared.persistence.TenantDataSourceProperties;
import ug.co.smsone.shared.persistence.TenantDataSources;
import ug.co.smsone.shared.tenancy.placement.PlacementState;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;

/**
 * The floor's own arithmetic and its memory (ADR 0010 §4.4), away from the servlet stack:
 * {@code CurrentUserFilterSchemaFloorTest} owns the 503 and the per-tenant guarantee against a real
 * registry, this owns what "below the floor" MEANS and what happens when the registry cannot say.
 *
 * <p>The registry is a mock here for one reason, and it is the reason ADR 0003's rule allows: three of
 * these cases — a read that throws, a registry one release ahead of this enum, a second call that must
 * NOT read at all — are states no fixture can put a real table into on demand, and each of them decides
 * whether a fleet stays up. The container-backed half of this gate is the filter test.
 */
class TenantSchemaFloorTest {

    private final TenantPlacements placements = mock(TenantPlacements.class);

    /**
     * A real registry over an empty {@code app.tenancy.datasources} map — the shipped shape, in which
     * only {@code 'primary'} is configured. Real rather than mocked because an empty registry builds no
     * pool and touches no database, and its {@code isConfigured} is the very logic under test in the
     * datasource-refusal case below.
     */
    private final TenantDataSources dataSources =
            new TenantDataSources(new HikariDataSource(), new TenantDataSourceProperties(Map.of()));

    private final TenantSchemaFloor floor =
            new TenantSchemaFloor(placements, dataSources, TenantSchemaFloor.RECHECK_AFTER);

    /**
     * The same floor with the shortest re-check the class will accept, for the two cases below that
     * have to WATCH it change its mind. One second is the clamp, not a taste: see the constructor.
     */
    private final TenantSchemaFloor impatient =
            new TenantSchemaFloor(placements, dataSources, Duration.ofSeconds(1));

    @Test
    void aTenantAtTheFloorIsServed() {
        assertThat(admits(String.valueOf(MIN_TENANT_SCHEMA_VERSION))).isTrue();
    }

    /** "At least" — a schema newer than this binary expects is the SAFE skew and is never refused. */
    @Test
    void aTenantAboveTheFloorIsServed() {
        assertThat(admits(String.valueOf(MIN_TENANT_SCHEMA_VERSION + 12))).isTrue();
    }

    /**
     * {@code schema_version} is {@code text} because a Flyway version is dot-separated, so {@code 53.1}
     * is a legal spelling of "past 53" that a string compare and an integer parse get wrong in opposite
     * directions. It is AT the floor of 53, not below it.
     */
    @Test
    void aDottedVersionAtTheFloorIsServed() {
        assertThat(admits(MIN_TENANT_SCHEMA_VERSION + ".1")).isTrue();
    }

    /** The whole point: one migration short is short. */
    @Test
    void oneVersionBelowTheFloorIsRefused() {
        assertThat(admits(String.valueOf(MIN_TENANT_SCHEMA_VERSION - 1))).isFalse();
    }

    /**
     * The shapes of "the registry cannot say", all served and all logged. Refusing on a fact we failed
     * to establish would convert one missing row into an outage for every tenant at once, which is the
     * exact failure the per-tenant check exists to avoid — and the NULL case is not hypothetical: V57's
     * backfill leaves every organization that predates it with a null version until the runner's first
     * pass, and V57's own column note requires that this be served.
     */
    @Test
    void aTenantTheRegistryCannotSpeakForIsServed() {
        assertThat(admits(null)).describedAs("version not recorded yet").isTrue();
        assertThat(admits("")).describedAs("empty version").isTrue();
        assertThat(admits("not-a-version")).describedAs("unparsable version").isTrue();

        UUID unknown = UUID.randomUUID();
        when(placements.find(unknown)).thenReturn(Optional.empty());
        assertThat(floor.admits(unknown)).describedAs("no placement row at all").isTrue();
    }

    @Test
    void aRegistryReadThatFailsServesTheTenant() {
        UUID orgId = UUID.randomUUID();
        when(placements.find(orgId))
                .thenThrow(new DataAccessResourceFailureException("platform tier unreachable"));

        assertThat(floor.admits(orgId)).isTrue();
    }

    /**
     * A registry one release AHEAD of this binary — a fourth {@code state} value, which
     * {@code PlacementState.of} refuses to parse — is the very skew §4.4 declares harmless, so it must
     * not be the thing that takes the fleet down from inside the check that exists to prevent exactly
     * that. Served, loudly.
     */
    @Test
    void aRegistryAheadOfThisBinaryServesTheTenant() {
        UUID orgId = UUID.randomUUID();
        when(placements.find(orgId)).thenThrow(new IllegalStateException(
                "platform.tenant_placement.state holds 'PROMOTING', which no PlacementState names"));

        assertThat(floor.admits(orgId)).isTrue();
    }

    /**
     * The hot-path guarantee. The fact settles at deploy time, so a second request for a tenant already
     * judged servable must not cost a round trip — {@code CurrentUserFilter} runs this on every
     * tenant-scoped call, and a query there is a query per request forever.
     */
    @Test
    void aServedTenantIsRememberedAndNotReadAgain() {
        UUID orgId = UUID.randomUUID();
        when(placements.find(orgId)).thenReturn(placedAt(orgId, String.valueOf(MIN_TENANT_SCHEMA_VERSION)));

        assertThat(floor.admits(orgId)).isTrue();
        assertThat(floor.admits(orgId)).isTrue();

        verify(placements, times(1)).find(orgId);
    }

    /**
     * No shared verdict anywhere: one tenant's lag is decided from one tenant's row. This is the unit of
     * the guarantee the filter test asserts end to end, and the thing a "has the fleet migrated yet"
     * boolean would quietly replace.
     */
    @Test
    void eachTenantIsJudgedOnItsOwnRow() {
        UUID lagging = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        when(placements.find(lagging))
                .thenReturn(placedAt(lagging, String.valueOf(MIN_TENANT_SCHEMA_VERSION - 1)));
        when(placements.find(current))
                .thenReturn(placedAt(current, String.valueOf(MIN_TENANT_SCHEMA_VERSION)));

        assertThat(floor.admits(lagging)).isFalse();
        assertThat(floor.admits(current)).isTrue();
        assertThat(floor.admits(lagging)).isFalse();
    }

    /**
     * {@code Retry-After} is the interval this process is next willing to re-read, not a round number
     * chosen separately — a client told to return sooner would meet the same remembered refusal and read
     * a recovering platform as a dead one.
     */
    @Test
    void theRetryAfterIsTheIntervalTheRefusalIsRememberedFor() {
        assertThat(floor.retryAfterSeconds()).isEqualTo(TenantSchemaFloor.RECHECK_AFTER.toSeconds());
        assertThat(floor.retryAfterSeconds()).isPositive();
    }

    /**
     * <b>ADR 0011 §4.2: a placement naming a datasource this deployment has no pool for refuses THAT
     * tenant — and unlike a missing version it is a refusal, not an unknown-so-serve.</b> The
     * difference is what was read: the nulls above are facts the registry could not supply, where this
     * row answered plainly with a name configuration cannot honour. Serving it would mean the borrow
     * path guessing a pool, which is ADR 0010 §1's misroute; a bounded per-tenant 503 is the safe
     * failure, and the fleet keeps serving — asserted here as the tenant beside it.
     */
    @Test
    void aPlacementNamingAnUnconfiguredDatasourceIsRefusedWhileTheFleetServes() {
        UUID marooned = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(placements.find(marooned)).thenReturn(Optional.of(new TenantPlacement(
                marooned, TenantSchemas.siloSchema(marooned), "nobody-configured-this",
                PlacementState.ACTIVE, String.valueOf(MIN_TENANT_SCHEMA_VERSION), null, Instant.now())));
        when(placements.find(healthy))
                .thenReturn(placedAt(healthy, String.valueOf(MIN_TENANT_SCHEMA_VERSION)));

        assertThat(floor.admits(marooned)).isFalse();
        assertThat(floor.admits(healthy))
                .describedAs("per tenant, never per pod — the whole point of refusing here")
                .isTrue();
    }

    /**
     * The refusal is remembered on the floor's own {@code RECHECK_AFTER} cadence, so a broken row is
     * one registry read per interval and not one per request — the memoization ADR 0011 §4.2 asks for
     * by name.
     */
    @Test
    void theDatasourceRefusalIsRememberedLikeAnyOther() {
        UUID marooned = UUID.randomUUID();
        when(placements.find(marooned)).thenReturn(Optional.of(new TenantPlacement(
                marooned, TenantSchemas.siloSchema(marooned), "nobody-configured-this",
                PlacementState.ACTIVE, null, null, Instant.now())));

        assertThat(floor.admits(marooned)).isFalse();
        assertThat(floor.admits(marooned)).isFalse();

        verify(placements, times(1)).find(marooned);
    }

    /**
     * <b>ADR 0011 §4.2, the half that had been folded into a verdict that never expired.</b> An
     * admission rests on two facts with different lifetimes: the tenant's {@code schema_version},
     * which cannot go backwards, and the row's {@code datasource_name}, which any cutover may rewrite
     * at any instant. Remembering the conjunction forever is remembering the wrong one — and the
     * failure it produced was not a stale 200 but a worse thing: the edge kept ADMITTING a tenant this
     * deployment could no longer route, so the refusal moved from a bounded 503 at the filter to an
     * {@code UnknownTenantDataSourceException} at the connection borrow, which the wire renders as
     * INTERNAL_ERROR with no {@code Retry-After}.
     *
     * <p>Asserted by moving the row underneath a tenant this process has already agreed to serve and
     * watching the answer change on its own, which is the only way to observe an expiry that happened.
     */
    @Test
    void anAdmissionIsReEarnedBecauseTheDatasourceHalfOfItCanChange() throws Exception {
        UUID moved = UUID.randomUUID();
        when(placements.find(moved))
                .thenReturn(placedAt(moved, String.valueOf(MIN_TENANT_SCHEMA_VERSION)));

        assertThat(impatient.admits(moved)).describedAs("served: pooled on primary, at the floor").isTrue();

        // The cutover another pod just ran: same tenant, same version, a datasource THIS deployment
        // has no pool for.
        when(placements.find(moved)).thenReturn(Optional.of(new TenantPlacement(
                moved, TenantSchemas.siloSchema(moved), "configured-on-other-pods-only",
                PlacementState.ACTIVE, String.valueOf(MIN_TENANT_SCHEMA_VERSION), null, Instant.now())));

        Thread.sleep(1_100);

        assertThat(impatient.admits(moved))
                .describedAs("the admission expired and the re-read found a pool this deployment does"
                        + " not have — refused at the EDGE, where the refusal is a 503, instead of"
                        + " being discovered at the borrow, where it is a 500")
                .isFalse();
    }

    /**
     * The other direction, and the one the operator lives through: a datasource refusal is not a
     * sentence for the life of the pod. A typo'd {@code datasource_name} corrected in the registry, or
     * a config rollback rolled forward, must serve the same tenant again <b>without a restart</b> —
     * the interval this process advertises as {@code Retry-After} is exactly when it will look.
     */
    @Test
    void aDatasourceRefusalLiftsItselfOnceTheRegistryAgrees() throws Exception {
        UUID marooned = UUID.randomUUID();
        when(placements.find(marooned)).thenReturn(Optional.of(new TenantPlacement(
                marooned, TenantSchemas.siloSchema(marooned), "nobody-configured-this",
                PlacementState.ACTIVE, String.valueOf(MIN_TENANT_SCHEMA_VERSION), null, Instant.now())));

        assertThat(impatient.admits(marooned)).isFalse();

        // The one-line repair, applied while the pod runs.
        when(placements.find(marooned))
                .thenReturn(placedAt(marooned, String.valueOf(MIN_TENANT_SCHEMA_VERSION)));

        Thread.sleep(1_100);

        assertThat(impatient.admits(marooned))
                .describedAs("no restart: the refusal expires on the interval its own Retry-After named")
                .isTrue();
    }

    /** A deployment that configured the interval gets that interval on the wire, not the default. */
    @Test
    void theAdvertisedRetryAfterFollowsTheConfiguredInterval() {
        assertThat(impatient.retryAfterSeconds()).isEqualTo(1);
    }

    private boolean admits(String schemaVersion) {
        UUID orgId = UUID.randomUUID();
        when(placements.find(orgId)).thenReturn(placedAt(orgId, schemaVersion));
        return floor.admits(orgId);
    }

    /** A pooled, ACTIVE tenant — everything about it is fine except, in some cases, its version. */
    private static Optional<TenantPlacement> placedAt(UUID orgId, String schemaVersion) {
        return Optional.of(new TenantPlacement(orgId, TenantSchemas.TENANT_POOL,
                TenantPlacement.PRIMARY_DATASOURCE, PlacementState.ACTIVE, schemaVersion, null,
                Instant.now()));
    }
}
