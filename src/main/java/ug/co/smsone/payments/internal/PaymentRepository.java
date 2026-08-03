package ug.co.smsone.payments.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<Payment> findByGatewayReference(String gatewayReference);
}
