package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import ug.co.smsone.organization.MembershipCreated;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The event → fan-out link: a published organization event enqueues a delivery for every matching
 * active subscription (via the {@code @ApplicationModuleListener}). Worker off — this asserts enqueue.
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
class WebhookEventTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = AbstractIntegrationTest.POSTGRES;

    static {
        POSTGRES.start();
    }

    @Autowired
    private WebhookSubscriptionService subscriptions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aMemberAddedEventEnqueuesADeliveryForASubscriber(Scenario scenario) {
        UUID orgId = UUID.randomUUID();
        subscriptions.create(orgId, "https://hooks.example.com/x", Set.of("org.member.added"));

        scenario.publish(new MembershipCreated(orgId, "kc-" + UUID.randomUUID(), "MEMBER", Instant.now()))
                .andWaitForStateChange(() -> enqueued(orgId) > 0 ? Boolean.TRUE : null)
                .andVerify(ready -> assertThat(enqueued(orgId)).isEqualTo(1));
    }

    private long enqueued(UUID orgId) {
        Long count = jdbc.queryForObject(
                "select count(*) from webhook_delivery where org_id = ? and event_type = 'org.member.added'",
                Long.class, orgId);
        return count == null ? 0 : count;
    }
}
