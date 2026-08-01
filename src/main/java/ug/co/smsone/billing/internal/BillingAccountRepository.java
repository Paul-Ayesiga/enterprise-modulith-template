package ug.co.smsone.billing.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BillingAccountRepository extends JpaRepository<BillingAccount, UUID> {

    Optional<BillingAccount> findByOrgId(UUID orgId);

    Optional<BillingAccount> findByKbAccountId(UUID kbAccountId);
}
