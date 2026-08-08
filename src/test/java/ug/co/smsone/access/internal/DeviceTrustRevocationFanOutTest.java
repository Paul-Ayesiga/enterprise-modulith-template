package ug.co.smsone.access.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.access.DeviceRevoked;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.promotion.TenantFreezes;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>A revoked device must leave no live trust grant behind — in a silo as well as in the pool</b>
 * (AGENTS §1, ADR 0010 §2.1 and §6, Phase 5).
 *
 * <p>{@code AccessPolicyTest} owns the behaviour and proves it across two POOLED organizations. It
 * would have gone on passing while a promoted tenant's grant outlived every revocation: the delete is
 * unqualified, so a listener pinned to {@code tenant_pool} removed the pool's rows, logged a healthy
 * count, and left the siloed grant vouching for a device the person had explicitly revoked. V53's own
 * header states the stakes — the enforcement lookup keys on {@code (org_id, person_id, fingerprint)}
 * and can no longer see {@code user_device.deleted_at}, so a surviving grant does not merely trust the
 * device a little too long, it trusts a re-registered fingerprint the organization has never seen.
 *
 * <p>The listener is invoked directly rather than through the event bus. What is under test is the
 * fan-out inside it, and driving it through {@code @Async} + {@code @TransactionalEventListener} would
 * add a commit boundary and an await for no extra coverage — {@code AccessPolicyTest} already proves
 * the event reaches this listener at all.
 */
class DeviceTrustRevocationFanOutTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    /**
     * The TARGET, not the bean. {@code on(DeviceRevoked)} carries {@code @Async}, so the injected
     * reference is a proxy that would hand the call to an executor and return immediately — every
     * assertion below would then race the work, and the second test's expected exception would be
     * thrown on a pool thread where nothing is watching for it. Unwrapping is what makes the fan-out
     * inside the method the thing under test rather than the proxy around it.
     */
    private DeviceTrustRevocationListener listener;

    @Autowired
    void unwrapTheAsyncProxy(DeviceTrustRevocationListener bean) {
        this.listener = org.springframework.test.util.AopTestUtils.getTargetObject(bean);
    }

    @Autowired
    private TenantFreezes freezes;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void revocationClearsTheGrantInASiloAsWellAsInThePool() {
        UUID personId = EdgeSeed.person(jdbc, "revoke-fanout-" + UUID.randomUUID());
        UUID deviceId = insertDevice(personId);
        UUID pooled = organization();
        UUID siloed = organization();
        silos.place(siloed);
        grant(pooled, deviceId, personId);
        grant(siloed, deviceId, personId);
        // Schema-qualified, before the listener runs: the siloed grant is really in t_<hex> and really
        // not in the pool. grant() writes and grants() reads through TenantContext, so without this the
        // assertions below would pass with both grants sitting in tenant_pool and the silo empty —
        // which is exactly the state a reverted fan-out leaves behind.
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "user_device_trust", "device_id", deviceId);

        listener.on(new DeviceRevoked(personId, deviceId, "fp-" + deviceId, java.time.Instant.now()));

        assertThat(grants(pooled, deviceId)).as("the pooled organization's grant goes, as it always did")
                .isZero();
        assertThat(grants(siloed, deviceId))
                .as("and so does the siloed organization's. Before the fan-out this grant survived every "
                        + "revocation forever and went on satisfying require_trusted_device")
                .isZero();
    }

    /**
     * A home the pass could not enter is a grant that outlived its device, so the listener FAILS rather
     * than reporting success over an incomplete sweep — which leaves the Modulith publication
     * incomplete and lets {@code OutboxResubmissionJob} retry once the promotion has thawed. The delete
     * is idempotent, so the retry costs nothing; a silent success would cost the security control.
     */
    @Test
    void aFrozenHomeFailsTheRevocationRatherThanReportingAnIncompleteSweepAsDone() {
        UUID personId = EdgeSeed.person(jdbc, "revoke-frozen-" + UUID.randomUUID());
        UUID deviceId = insertDevice(personId);
        UUID promoting = organization();
        grant(promoting, deviceId, personId);
        freezes.freeze(promoting, "promotion under test", "DeviceTrustRevocationFanOutTest",
                Duration.ofMinutes(5));

        try {
            assertThatThrownBy(() ->
                    listener.on(new DeviceRevoked(personId, deviceId, "fp-" + deviceId, java.time.Instant.now())))
                    .as("an incomplete revocation must not look like a completed one")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("frozen");
            assertThat(grants(promoting, deviceId))
                    .as("and the grant is still there, which is exactly why the failure has to be loud")
                    .isOne();
        } finally {
            freezes.thaw(promoting);
            TenantContext.runAs(promoting, () ->
                    jdbc.update("delete from user_device_trust where device_id = ?", deviceId));
        }
    }

    private UUID organization() {
        return EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "revoke-" + UUID.randomUUID());
    }

    /**
     * {@code user_device} is platform-tier (docs/DATA_MODEL.md, {@code UserDevice}'s
     * {@code @Table(schema = "platform")}, {@code db/migration/platform/V31}), so it is named
     * {@code platform.} and resolves from the harness's PLATFORM axis — or from any other.
     *
     * <p><strong>And it has no {@code trusted} column.</strong> V51 moved trust off the device and onto
     * {@code user_device_trust}, one grant per organization, which is the very split this class exists
     * to fan out over: {@code db/migration/platform/V51} drops the boolean in the same slice its tenant
     * half creates the table. Listing it here made both tests fail before either reached its subject,
     * with {@code column "trusted" of relation "user_device" does not exist} — which reads as a routing
     * failure and is a stale column list.
     */
    private UUID insertDevice(UUID personId) {
        UUID deviceId = UUID.randomUUID();
        jdbc.update("""
                insert into platform.user_device (id, person_id, name, kind, fingerprint,
                                                  version, created_at)
                values (?, ?, 'Fan-out probe', 'BROWSER', ?, 0, now())
                """, deviceId, personId, "fp-" + deviceId);
        return deviceId;
    }

    /**
     * The grant is the TENANT's row and carries the denormalized {@code person_id}/{@code fingerprint}
     * V53 added when the cross-tier foreign key was cut — so for a placed org the whole row lands in
     * {@code t_<hex>} and nothing in it can see the device's own {@code deleted_at}.
     */
    private void grant(UUID orgId, UUID deviceId, UUID personId) {
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into user_device_trust (device_id, org_id, person_id, fingerprint, granted_at)
                values (?, ?, ?, ?, now())
                """, deviceId, orgId, personId, "fp-" + deviceId));
    }

    private int grants(UUID orgId, UUID deviceId) {
        return TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from user_device_trust where device_id = ?", Integer.class, deviceId));
    }
}
