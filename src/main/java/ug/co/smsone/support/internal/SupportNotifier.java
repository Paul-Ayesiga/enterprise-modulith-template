package ug.co.smsone.support.internal;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.notification.Recipient;

/** Thin notification seam for support events, so the service stays about ticket logic. */
@Component
class SupportNotifier {

    private final Notifications notifications;

    SupportNotifier(Notifications notifications) {
        this.notifications = notifications;
    }

    void ticketOpened(Ticket ticket) {
        notifications.notifyAdmins("[Support] New " + ticket.getPriority() + " ticket: " + ticket.getSubject(),
                "A new support ticket was opened (priority " + ticket.getPriority() + ").");
    }

    /**
     * The opener hears "you have a reply" in-app — the JobCompleted notifier pattern. The in-app
     * target is the opener's {@code person.id}, which is what {@code in_app_notification.person_id}
     * holds; the named factory keeps that fact in one place instead of this call site deciding how a
     * person is spelled on the wire to notifications.
     */
    void ticketReplied(Ticket ticket) {
        notifications.dispatch(new NotificationRequest(
                "Reply on your support ticket", "Support replied to your ticket: " + ticket.getSubject(),
                List.of(Recipient.inApp(ticket.getOpenerPersonId())),
                Map.of("orgId", ticket.getOrgId().toString())));
    }

    /**
     * ONE notification for a whole escalation sweep, deliberately — not one per breached ticket.
     *
     * <p>{@code notifyAdmins} re-resolves the admin roster on every call (a {@code person} lookup per
     * configured administrator, so the roster's identities are never stale config), which is right
     * for a one-off operational event and wrong inside a loop: the sweep escalates up to a batch of
     * tickets at a time, and calling this per ticket paid that resolution once per ticket for a
     * roster that cannot change during the sweep. The digest is how the roster is hoisted OUT of the
     * loop — the loop now notifies nobody — and it also spares the on-call a hundred separate mails
     * for what is one incident.
     */
    void ticketsEscalated(List<Ticket> escalated) {
        if (escalated.isEmpty()) {
            return;
        }
        // StringBuilder(int) would set the CAPACITY, not the text — start from the sentence itself.
        StringBuilder body = new StringBuilder(escalated.size() == 1
                ? "A ticket breached its SLA and was escalated:"
                : escalated.size() + " tickets breached their SLA and were escalated:");
        for (Ticket ticket : escalated) {
            body.append("\n- [").append(ticket.getPriority()).append("] ").append(ticket.getSubject())
                    .append(" (").append(ticket.getId()).append(')');
        }
        notifications.notifyAdmins(
                "[Support] SLA BREACH — " + escalated.size() + " ticket(s) escalated", body.toString());
    }
}
