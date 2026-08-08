package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Rotation against the real store: the new plaintext verifies once, differs from the old, and the
 * row's ciphertext actually changed — the old secret is dead the moment the call returns.
 *
 * <p>Every call below runs on the org's axis, because {@code webhook_subscription} is tenant-tier
 * (ADR 0010 §2) and the harness pins PLATFORM. The service calls need it as much as the raw reads do:
 * {@code create} and {@code rotateSecret} are {@code @Transactional}, so the axis has to be declared
 * before they borrow a connection — {@code TenantContext} refuses a pin made inside one.
 */
class WebhookSecretRotationTest extends AbstractIntegrationTest {

    @Autowired
    private WebhookSubscriptionService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rotateMintsANewSecretAndRetiresTheOld() {
        UUID orgId = UUID.randomUUID();
        WebhookSubscriptionService.CreatedSubscription created = TenantContext.callAs(orgId,
                () -> service.create(orgId, "https://rotate.example/hook", Set.of("org.member.added")));
        String before = storedSecret(orgId, created.subscription().getId());

        WebhookSubscriptionService.CreatedSubscription rotated = TenantContext.callAs(orgId,
                () -> service.rotateSecret(orgId, created.subscription().getId()));

        assertThat(rotated.plainSecret()).startsWith("whsec_").isNotEqualTo(created.plainSecret());
        String after = storedSecret(orgId, created.subscription().getId());
        assertThat(after).isNotEqualTo(before);
    }

    private String storedSecret(UUID orgId, UUID subscriptionId) {
        return TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select secret from webhook_subscription where id = ?", String.class, subscriptionId));
    }
}
