package ug.co.smsone.billing.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.notification.Recipient;
import ug.co.smsone.organization.OrgContacts;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.subscription.SubscriptionChanged;

/**
 * Dunning's voice: the org's OWNERS hear about commercial-standing trouble the moment it happens —
 * PAST_DUE (a payment failed; access continues through the grace window) and PAUSED (the deadline
 * hit; the org is read-only, billing stays open as the way back). Owners resolve fresh per event;
 * the orgId rides the metadata so channel routing honors the org's own provider choices. Async and
 * registry-backed like every listener — a mail outage defers, never drops.
 */
@Component
class BillingStandingNotifier {

    private static final Logger log = LoggerFactory.getLogger(BillingStandingNotifier.class);

    private final ObjectProvider<OrgContacts> contacts;
    private final ObjectProvider<Notifications> notifications;

    BillingStandingNotifier(ObjectProvider<OrgContacts> contacts, ObjectProvider<Notifications> notifications) {
        this.contacts = contacts;
        this.notifications = notifications;
    }

    /**
     * {@code @Async} + {@code @TransactionalEventListener} rather than
     * {@link ApplicationModuleListener}, so the tenant axis can be declared before a connection is
     * borrowed — see {@link #send}, which resolves the owners out of tenant-tier tables.
     */
    @Async
    @TransactionalEventListener
    void on(SubscriptionChanged event) {
        String subject;
        String body;
        switch (event.status() == null ? "" : event.status()) {
            case "PAST_DUE" -> {
                subject = "Action needed: a payment for your organization failed";
                body = "A payment for your organization did not go through. Your plan ("
                        + planName(event) + ") keeps full access during the grace window — please "
                        + "update your payment method or pay an open invoice to avoid a pause.";
            }
            case "PAUSED" -> {
                subject = "Your organization is paused (read-only)";
                body = "The grace window ended without a successful payment, so your organization is "
                        + "now read-only. Billing stays open: settle an open invoice or make a "
                        + "payment and access resumes immediately.";
            }
            default -> {
                return; // plan assignments, trials, recoveries — not dunning's business
            }
        }
        send(event.orgId(), subject, body);
    }

    /**
     * Resolving the owners is TENANT work: {@code OrgContacts.ownerEmails} walks {@code org_role} and
     * {@code membership}, both tenant-tier (ADR 0010 §2), before it reaches the platform-tier person
     * rows their mapping names explicitly. So the whole lookup runs on the org's axis, which is the
     * one the event carries. The dispatch is left outside it on purpose: a notification is the
     * platform's queue, and this method has already turned the tenant's rows into plain addresses.
     */
    private void send(UUID orgId, String subject, String body) {
        OrgContacts directory = contacts.getIfAvailable();
        Notifications sender = notifications.getIfAvailable();
        if (directory == null || sender == null) {
            return;
        }
        List<Recipient> owners = TenantContext.callAs(orgId,
                () -> directory.ownerEmails(orgId).stream().map(Recipient::email).toList());
        if (owners.isEmpty()) {
            log.warn("Standing change for org {} but no owner email to notify", orgId);
            return;
        }
        sender.dispatch(new NotificationRequest(subject, body, owners, Map.of("orgId", orgId.toString())));
    }

    private static String planName(SubscriptionChanged event) {
        return event.planCode() == null ? "your plan" : event.planCode();
    }
}
