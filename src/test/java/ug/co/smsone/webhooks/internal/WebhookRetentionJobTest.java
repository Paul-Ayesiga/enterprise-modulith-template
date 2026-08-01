package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The retention that three in-repo claims described before anything ran it. Terminal rows past the
 * window go; queued work and everything inside the window is never retention's business.
 */
class WebhookRetentionJobTest extends AbstractIntegrationTest {

    @Autowired
    private WebhookSubscriptionService subscriptions;

    @Autowired
    private WebhookRetentionJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purgesOldTerminalRowsAndNothingElse() {
        UUID orgId = UUID.randomUUID();
        WebhookSubscription subscription = subscriptions.create(orgId,
                "http://127.0.0.1:1/unreachable", Set.of("org.member.added"));
        UUID oldDelivered = insert(subscription.getId(), orgId, "DELIVERED", "40 days");
        UUID oldFailed = insert(subscription.getId(), orgId, "FAILED", "40 days");
        UUID oldPending = insert(subscription.getId(), orgId, "PENDING", "40 days");
        UUID youngDelivered = insert(subscription.getId(), orgId, "DELIVERED", "1 day");

        job.purgeExpiredDeliveries();

        assertThat(exists(oldDelivered)).as("DELIVERED past retention goes").isFalse();
        assertThat(exists(oldFailed)).as("dead-letters age out too — the log is not an archive").isFalse();
        assertThat(exists(oldPending)).as("queued work is never retention's business").isTrue();
        assertThat(exists(youngDelivered)).as("inside the window stays").isTrue();
    }

    private UUID insert(UUID subscriptionId, UUID orgId, String status, String age) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into webhook_delivery (id, subscription_id, org_id, event_type, payload, "
                        + "status, attempts, max_attempts, next_attempt_at, created_at) "
                        + "values (?, ?, ?, 'org.member.added', '{}', ?, 1, 5, now(), now() - interval '" + age + "')",
                id, subscriptionId, orgId, status);
        return id;
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from webhook_delivery where id = ?)", Boolean.class, id));
    }
}
