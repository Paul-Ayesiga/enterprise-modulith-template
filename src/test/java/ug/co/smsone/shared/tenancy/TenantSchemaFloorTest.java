package ug.co.smsone.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ug.co.smsone.shared.tenancy.TenantSchemaFloor.MIN_TENANT_SCHEMA_VERSION;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
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
    private final TenantSchemaFloor floor = new TenantSchemaFloor(placements);

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
