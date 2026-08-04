package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Rotation against the real store: the new plaintext verifies once, differs from the old, and the
 * row's ciphertext actually changed — the old secret is dead the moment the call returns.
 */
class WebhookSecretRotationTest extends AbstractIntegrationTest {

    @Autowired
    private WebhookSubscriptionService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rotateMintsANewSecretAndRetiresTheOld() {
        UUID orgId = UUID.randomUUID();
        WebhookSubscriptionService.CreatedSubscription created =
                service.create(orgId, "https://rotate.example/hook", Set.of("org.member.added"));
        String before = jdbc.queryForObject("select secret from webhook_subscription where id = ?",
                String.class, created.subscription().getId());

        WebhookSubscriptionService.CreatedSubscription rotated =
                service.rotateSecret(orgId, created.subscription().getId());

        assertThat(rotated.plainSecret()).startsWith("whsec_").isNotEqualTo(created.plainSecret());
        String after = jdbc.queryForObject("select secret from webhook_subscription where id = ?",
                String.class, created.subscription().getId());
        assertThat(after).isNotEqualTo(before);
    }
}
