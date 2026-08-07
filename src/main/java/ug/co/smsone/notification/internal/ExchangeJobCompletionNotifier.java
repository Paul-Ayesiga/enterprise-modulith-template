package ug.co.smsone.notification.internal;

import java.util.List;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.exchange.JobCompleted;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.notification.Recipient;
import ug.co.smsone.shared.events.EventInbox;

/**
 * "Your import finished" — the {@link JobCompleted} consumer the exchange module's event catalog
 * always named as the natural first one. In-app to the REQUESTER (the subject that submitted the
 * job), idempotent via {@link EventInbox}: a job terminates once (fenced), so the id alone keys
 * the dedup; {@code occurredAt} rides along for uniformity with the sibling notifiers.
 */
@Component
class ExchangeJobCompletionNotifier {

    private static final String LISTENER_ID = "notification-exchange-job";

    private final Notifications notifications;
    private final EventInbox inbox;

    ExchangeJobCompletionNotifier(Notifications notifications, EventInbox inbox) {
        this.notifications = notifications;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    void on(JobCompleted event) {
        String messageId = "exchange-job:" + event.jobId() + "@" + event.occurredAt();
        if (!inbox.recordIfNew(LISTENER_ID, messageId)) {
            return;
        }
        String what = event.jobType().equalsIgnoreCase("EXPORT") ? "export" : "import";
        String subject = "Your " + event.handler() + " " + what + " " + humanize(event.outcome());
        String body = "Job " + event.jobId() + " (" + event.handler() + " " + what + ") finished as "
                + event.outcome() + ": " + event.processed() + " records processed, "
                + event.failed() + " failed.";
        notifications.dispatch(new NotificationRequest(subject, body,
                List.of(Recipient.inApp(event.requesterPersonId())),
                Map.of("orgId", event.orgId().toString())));
    }

    private static String humanize(String outcome) {
        return switch (outcome) {
            case "COMPLETED" -> "completed";
            case "COMPLETED_WITH_ERRORS" -> "completed with errors";
            case "FAILED" -> "failed";
            case "CANCELLED" -> "was cancelled";
            default -> outcome.toLowerCase();
        };
    }
}
