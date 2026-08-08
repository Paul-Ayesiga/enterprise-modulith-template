package ug.co.smsone.billing.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.notification.Recipient;
import ug.co.smsone.organization.OrgContacts;

/**
 * The receipt: a successful Kill Bill payment mails the org's owners a summary of the freshest
 * invoice (number, amount, remaining balance). Deliberately best-effort — a receipt must never make
 * a payment path fail, so every miss degrades to a log line.
 *
 * <p><strong>Called on the org's own axis, and it does not pin one for itself.</strong> Both reads
 * here are tenant-tier: {@code billing_account} directly, and {@code OrgContacts.ownerEmails} through
 * {@code org_role} + {@code membership} (ADR 0010 §2 — the person rows it ends at are platform-tier
 * and named as such by their mapping, so one tenant axis reaches the whole chain). Its only caller is
 * {@code BillingService.onPaymentOutcome}, which has already entered the tenant it found. A pin here
 * would be a second, redundant spelling of that — and a wrong one the day this is called from inside
 * a transaction.
 */
@Component
class BillingReceipts {

    private static final Logger log = LoggerFactory.getLogger(BillingReceipts.class);

    private final BillingAccountRepository accounts;
    private final KillBillGateway killBill;
    private final ObjectProvider<OrgContacts> contacts;
    private final ObjectProvider<Notifications> notifications;

    BillingReceipts(BillingAccountRepository accounts, KillBillGateway killBill,
            ObjectProvider<OrgContacts> contacts, ObjectProvider<Notifications> notifications) {
        this.accounts = accounts;
        this.killBill = killBill;
        this.contacts = contacts;
        this.notifications = notifications;
    }

    void paymentReceived(UUID orgId) {
        try {
            OrgContacts directory = contacts.getIfAvailable();
            Notifications sender = notifications.getIfAvailable();
            if (directory == null || sender == null) {
                return;
            }
            List<Recipient> owners = directory.ownerEmails(orgId).stream().map(Recipient::email).toList();
            if (owners.isEmpty()) {
                return;
            }
            String summary = accounts.findByOrgId(orgId)
                    .map(account -> killBill.invoices(account.getKbAccountId()))
                    .filter(invoices -> !invoices.isEmpty())
                    .map(invoices -> invoices.get(0))
                    .map(invoice -> "Invoice #" + invoice.invoiceNumber() + " — " + invoice.amount()
                            + " " + invoice.currency()
                            + (invoice.balance() != null && invoice.balance().signum() > 0
                                    ? " (remaining balance " + invoice.balance() + ")" : " (settled)"))
                    .orElse("Your account balance has been updated.");
            sender.dispatch(new NotificationRequest(
                    "Payment received — thank you",
                    "We received your payment. " + summary
                            + "\n\nFull history is available on your billing page.",
                    owners, Map.of("orgId", orgId.toString())));
        } catch (RuntimeException e) {
            log.warn("Receipt for org {} not sent: {}", orgId, e.toString());
        }
    }
}
