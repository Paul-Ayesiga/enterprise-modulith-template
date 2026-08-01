package ug.co.smsone.notification.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/** In-app notifications for the authenticated user (cursor-paginated); mark-as-read. */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private static final String RESOURCE_TYPE = "notification";

    private final InAppNotificationService service;

    NotificationController(InAppNotificationService service) {
        this.service = service;
    }

    record NotificationAttributes(String subject, String body, boolean read, Instant createdAt) {
    }

    @GetMapping
    @Operation(summary = "List your in-app notifications")
    WindowedResult<ResourceObject> list(CursorPageRequest page) {
        return WindowedResult.of(service.listForCurrentUser(page), page, NotificationController::toResource);
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one of your notifications as read",
            description = """
                    Idempotent — marking an already-read notification returns it unchanged rather than \
                    failing. Someone else's notification is a 404, not a 403.""")
    ResourceObject markRead(@PathVariable UUID id) {
        return toResource(service.markRead(id));
    }

    private static ResourceObject toResource(InAppNotification notification) {
        return new ResourceObject(
                notification.getId().toString(),
                RESOURCE_TYPE,
                new NotificationAttributes(
                        notification.getSubject(),
                        notification.getBody(),
                        notification.isRead(),
                        notification.getCreatedAt()));
    }
}
