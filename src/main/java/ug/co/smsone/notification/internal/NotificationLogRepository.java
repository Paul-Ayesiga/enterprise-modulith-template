package ug.co.smsone.notification.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {
}
