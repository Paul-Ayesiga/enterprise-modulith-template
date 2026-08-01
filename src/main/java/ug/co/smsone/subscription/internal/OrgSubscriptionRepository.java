package ug.co.smsone.subscription.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrgSubscriptionRepository extends JpaRepository<OrgSubscription, UUID> {

    Optional<OrgSubscription> findByOrgId(UUID orgId);
}
