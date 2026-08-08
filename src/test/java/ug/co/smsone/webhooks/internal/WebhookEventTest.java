package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ug.co.smsone.organization.MembershipCreated;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.TenantAxisExtension;

/**
 * The event → fan-out link: a published organization event enqueues a delivery for every matching
 * active subscription (via {@link WebhookEventListener}, async and after the publisher commits).
 * Worker off — this asserts enqueue.
 *
 * <p>{@code @ExtendWith(TenantAxisExtension.class)} because this class bootstraps its own slice rather
 * than extending {@code AbstractIntegrationTest}, and so would otherwise run with no tenant axis
 * (ADR 0010 §3.4). What the extension pins is PLATFORM, and every table this test touches is
 * TENANT-tier — {@code webhook_subscription} and {@code webhook_delivery} both — so each call below
 * declares the org's own axis. Nothing here is a formality: on the harness's pin the create fails with
 * {@code relation "webhook_subscription" does not exist}.
 *
 * <p><b>What the extension deliberately does NOT cover is the assertion this class exists to make.</b>
 * The listener that does the enqueueing runs on the {@code @Async} executor — a pooled platform thread
 * nothing pins — and it owes itself an axis taken from the event's {@code orgId} (ADR 0010 §3.2).
 * Delete {@code WebhookEventListener.fanOut}'s {@code runAs} and the wait below times out on a
 * delivery that was never enqueued, because the fan-out's read of {@code webhook_subscription} lands in
 * the empty {@code no_tenant} schema. That is the whole point of publishing the event rather than
 * calling the dispatcher.
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
@ExtendWith(TenantAxisExtension.class)
class WebhookEventTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = AbstractIntegrationTest.POSTGRES;

    @Autowired
    private WebhookSubscriptionService subscriptions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aMemberAddedEventEnqueuesADeliveryForASubscriber(Scenario scenario) {
        UUID orgId = UUID.randomUUID();
        // The org's own axis, declared outside the service's @Transactional boundary — the subscription
        // is that tenant's row. A bare uuid with no `organization` behind it is fine and stays fine: an
        // org that was never promoted resolves to tenant_pool, and one that does not exist can only ever
        // be unpromoted.
        TenantContext.runAs(orgId,
                () -> subscriptions.create(orgId, "https://hooks.example.com/x", Set.of("org.member.added")));

        scenario.publish(new MembershipCreated(orgId, UUID.randomUUID(), "MEMBER", Instant.now()))
                .andWaitForStateChange(() -> enqueued(orgId) > 0 ? Boolean.TRUE : null)
                .andVerify(ready -> assertThat(enqueued(orgId)).isEqualTo(1));
    }

    /**
     * Pinned twice over. {@code webhook_delivery} is TENANT-tier, so the org is what the read needs —
     * the argument was already here for the day Phase 2 made it load-bearing. And
     * {@code andWaitForStateChange} evaluates this on Awaitility's OWN thread, a pooled platform thread
     * nothing pins, so without any pin the borrow routes to the empty {@code no_tenant} schema and the
     * count fails with {@code relation "webhook_delivery" does not exist} before the wait can ever be
     * satisfied (ADR 0010 §3.4). The {@code andVerify} call that follows runs back on the test thread,
     * where this is a re-declaration of an axis that thread does not hold either — the harness pinned
     * PLATFORM, which cannot see this table at all.
     */
    private long enqueued(UUID orgId) {
        Long count = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from webhook_delivery where org_id = ? and event_type = 'org.member.added'",
                Long.class, orgId));
        return count == null ? 0 : count;
    }
}
