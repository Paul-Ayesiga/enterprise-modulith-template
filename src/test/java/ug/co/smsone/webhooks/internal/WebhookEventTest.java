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
 * active subscription (via the {@code @ApplicationModuleListener}). Worker off — this asserts enqueue.
 *
 * <p>{@code @ExtendWith(TenantAxisExtension.class)} because this class bootstraps its own slice rather
 * than extending {@code AbstractIntegrationTest}, and so would otherwise run with no tenant axis
 * (ADR 0010 §3.4). Note what the extension deliberately does NOT cover: the listener that does the
 * enqueueing runs on the {@code @Async} executor, a pooled platform thread the harness never pins, and
 * it owes itself an axis taken from the event's {@code orgId} (ADR 0010 §3.2).
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
        subscriptions.create(orgId, "https://hooks.example.com/x", Set.of("org.member.added")).subscription();

        scenario.publish(new MembershipCreated(orgId, UUID.randomUUID(), "MEMBER", Instant.now()))
                .andWaitForStateChange(() -> enqueued(orgId) > 0 ? Boolean.TRUE : null)
                .andVerify(ready -> assertThat(enqueued(orgId)).isEqualTo(1));
    }

    /**
     * Pinned because {@code andWaitForStateChange} evaluates this on Awaitility's OWN thread — a
     * pooled platform thread the harness never pins, so the borrow routes to the empty
     * {@code no_tenant} schema and the count fails with {@code relation "webhook_delivery" does not
     * exist} before the wait can ever be satisfied (ADR 0010 §3.4). The {@code andVerify} call that
     * follows runs back on the test thread, where the pin is a harmless re-declaration of the axis it
     * already holds. PLATFORM today; when Phase 2 moves {@code webhook_delivery} to the tenant tier
     * this becomes {@code callAs(orgId, …)} — the argument is already here.
     */
    private long enqueued(UUID orgId) {
        Long count = TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select count(*) from webhook_delivery where org_id = ? and event_type = 'org.member.added'",
                Long.class, orgId));
        return count == null ? 0 : count;
    }
}
