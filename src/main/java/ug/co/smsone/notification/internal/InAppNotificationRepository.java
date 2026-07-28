package ug.co.smsone.notification.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InAppNotificationRepository
        extends JpaRepository<InAppNotification, UUID>, JpaSpecificationExecutor<InAppNotification> {

    Optional<InAppNotification> findByIdAndRecipient(UUID id, String recipient);

    /**
     * Idempotent conditional mark-read. A bulk update on purpose: entity save() would bump
     * {@code @Version} and turn two concurrent mark-reads of the same row into an
     * optimistic-lock 500; here the loser is simply a no-op.
     */
    @Modifying(clearAutomatically = true)
    @Query("update InAppNotification n set n.readAt = :when"
            + " where n.id = :id and n.recipient = :recipient and n.readAt is null")
    int markReadIfUnread(@Param("id") UUID id, @Param("recipient") String recipient, @Param("when") Instant when);
}
