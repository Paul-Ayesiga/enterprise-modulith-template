package ug.co.smsone.webhooks.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface WebhookSubscriptionRepository
        extends JpaRepository<WebhookSubscription, UUID>, JpaSpecificationExecutor<WebhookSubscription> {

    List<WebhookSubscription> findByOrgId(UUID orgId);

    List<WebhookSubscription> findByOrgIdAndStatus(UUID orgId, SubscriptionStatus status);

    Optional<WebhookSubscription> findByIdAndOrgId(UUID id, UUID orgId);
}
