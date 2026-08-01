package ug.co.smsone.webhooks.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WebhookSubscriptionRepository
        extends JpaRepository<WebhookSubscription, UUID>, JpaSpecificationExecutor<WebhookSubscription> {

    List<WebhookSubscription> findByOrgId(UUID orgId);

    long countByOrgId(UUID orgId);

    List<WebhookSubscription> findByOrgIdAndStatus(UUID orgId, SubscriptionStatus status);

    Optional<WebhookSubscription> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Tenant check that also matches a deleted subscription — native because {@code @SQLRestriction}
     * is applied to every HQL query and would hide exactly the rows this exists to find. Deliberately
     * returns a boolean rather than the entity: only the delivery-log read needs to see past the
     * delete, and handing out a deleted aggregate invites someone to mutate it.
     */
    @Query(value = "select exists(select 1 from webhook_subscription where id = :id and org_id = :orgId)",
            nativeQuery = true)
    boolean existsIncludingDeleted(@Param("id") UUID id, @Param("orgId") UUID orgId);
}
