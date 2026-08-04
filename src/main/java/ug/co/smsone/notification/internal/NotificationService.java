package ug.co.smsone.notification.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import ug.co.smsone.identity.UserDirectory;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.notification.Recipient;

/**
 * Public entry point. {@code dispatch} does not send — it durably enqueues one delivery per
 * recipient/channel and returns immediately (non-blocking); the {@link NotificationDeliveryWorker}
 * fans them out asynchronously. This is what lets a single call target thousands of recipients
 * without blocking the caller or holding resources.
 */
@Service
class NotificationService implements Notifications {

    private final NotificationDeliveryQueue queue;
    private final NotificationProperties properties;
    private final UserDirectory userDirectory;

    NotificationService(NotificationDeliveryQueue queue, NotificationProperties properties,
            UserDirectory userDirectory) {
        this.queue = queue;
        this.properties = properties;
        this.userDirectory = userDirectory;
    }

    @Override
    public void dispatch(NotificationRequest request) {
        if (request.recipients().isEmpty()) {
            return;
        }
        // The org context (metadata "orgId") rides each row so channel senders can resolve the
        // org's own integration choice (e.g. WHICH SMS provider) at send time.
        java.util.UUID orgId = parseOrgId(request.metadata().get("orgId"));
        List<NewDelivery> deliveries = request.recipients().stream()
                .map(recipient -> new NewDelivery(recipient.channel(), recipient.address(),
                        request.subject(), request.body(), orgId))
                .toList();
        queue.enqueue(deliveries, properties.delivery().maxAttempts());
    }

    @Override
    public void notifyAdmins(String subject, String body) {
        List<Recipient> recipients = new ArrayList<>();
        for (NotificationProperties.Admin admin : properties.admins()) {
            recipients.add(Recipient.email(admin.email()));
            // In-app targets are addressed by the immutable Keycloak subject, never by a
            // mutable/recyclable name — resolved from the admin's email at enqueue time. An
            // unprovisioned admin can't log in to read in-app anyway; e-mail still goes out.
            userDirectory.findSubjectByEmail(admin.email())
                    .ifPresent(adminSubject -> recipients.add(Recipient.inApp(adminSubject)));
        }
        if (recipients.isEmpty()) {
            return;
        }
        dispatch(new NotificationRequest(subject, body, recipients, Map.of()));
    }

    private static java.util.UUID parseOrgId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null; // metadata is caller-supplied — a bad id degrades to platform-default routing
        }
    }
}
