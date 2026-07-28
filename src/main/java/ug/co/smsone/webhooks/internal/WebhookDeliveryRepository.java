package ug.co.smsone.webhooks.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface WebhookDeliveryRepository
        extends JpaRepository<WebhookDelivery, UUID>, JpaSpecificationExecutor<WebhookDelivery> {
}
