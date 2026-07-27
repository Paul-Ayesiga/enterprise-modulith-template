package ug.co.smsone.notification.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface InAppNotificationRepository
        extends JpaRepository<InAppNotification, UUID>, JpaSpecificationExecutor<InAppNotification> {

    Optional<InAppNotification> findByIdAndRecipient(UUID id, String recipient);
}
