package ug.co.smsone.billing.internal;

import java.math.BigDecimal;
import java.util.List;
import ug.co.smsone.shared.web.ResourceObject;

/** One resource shape for both billing surfaces. */
final class BillingResources {

    record BillingAttributes(String kbAccountId, BigDecimal balance, String currency,
            List<SubscriptionLine> subscriptions, List<String> paymentMethods) {
    }

    record SubscriptionLine(String planName, String state) {
    }

    record InvoiceAttributes(String invoiceNumber, BigDecimal amount, BigDecimal balance,
            String currency, String invoiceDate, String status) {
    }

    record PaymentMethodAttributes(String pluginName, boolean isDefault) {
    }

    private BillingResources() {
    }

    static ResourceObject toResource(BillingService.BillingView view) {
        return new ResourceObject(view.orgId().toString(), "billing",
                new BillingAttributes(view.kbAccountId().toString(), view.balance(), view.currency(),
                        view.subscriptions().stream()
                                .map(sub -> new SubscriptionLine(sub.planName(), sub.state()))
                                .toList(),
                        view.paymentMethods()));
    }

    static List<ResourceObject> toInvoiceResources(List<KillBillGateway.KbInvoice> invoices) {
        return invoices.stream()
                .map(invoice -> new ResourceObject(invoice.invoiceId(), "invoice",
                        new InvoiceAttributes(invoice.invoiceNumber(), invoice.amount(),
                                invoice.balance(), invoice.currency(), invoice.invoiceDate(),
                                invoice.status())))
                .toList();
    }

    static List<ResourceObject> toPaymentMethodResources(List<KillBillGateway.KbPaymentMethod> methods) {
        return methods.stream()
                .map(method -> new ResourceObject(method.paymentMethodId().toString(), "payment-method",
                        new PaymentMethodAttributes(method.pluginName(), method.isDefault())))
                .toList();
    }
}
