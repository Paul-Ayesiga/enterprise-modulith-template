package ug.co.smsone.support.internal;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannel;
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

    /** The opener hears "you have a reply" in-app — the JobCompleted notifier pattern. */
    void ticketReplied(Ticket ticket) {
        notifications.dispatch(new NotificationRequest(
                "Reply on your support ticket", "Support replied to your ticket: " + ticket.getSubject(),
                List.of(new Recipient(NotificationChannel.IN_APP, ticket.getOpenerSubject())), Map.of()));
    }

    void ticketEscalated(Ticket ticket) {
        notifications.notifyAdmins("[Support] SLA BREACH — ticket escalated to " + ticket.getPriority(),
                "A ticket breached its SLA and was escalated: " + ticket.getSubject());
    }
}
