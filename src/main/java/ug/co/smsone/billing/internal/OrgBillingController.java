package ug.co.smsone.billing.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The tenant's own money view: invoices, and payment-method management. Changing plans is the
 * platform's surface; managing how the org PAYS is the org's own (org:update). This surface stays
 * reachable while a subscription is paused, so a tenant can add a payment method to recover.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/billing")
class OrgBillingController {

    private final BillingService billing;

    OrgBillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/invoices")
    @Operation(summary = "List the organization's invoices",
            description = "Proxied from Kill Bill, un-paged. 404 until the platform provisions billing.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    List<ResourceObject> invoices(@PathVariable UUID orgId) {
        return BillingResources.toInvoiceResources(billing.invoices(orgId));
    }

    record PaymentMethodRequest(String pluginName, Boolean isDefault, Map<String, Object> pluginProperties) {
    }

    @GetMapping("/payment-methods")
    @Operation(summary = "List the organization's payment methods (from Kill Bill)")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    List<ResourceObject> paymentMethods(@PathVariable UUID orgId) {
        return BillingResources.toPaymentMethodResources(billing.paymentMethods(orgId));
    }

    @PostMapping("/payment-methods")
    @Operation(summary = "Add a payment method",
            description = "Attaches a payment method through a Kill Bill payment PLUGIN. Raw card data "
                    + "never touches this API — `pluginProperties` carries only what a hosted page / "
                    + "plugin tokenized; the card-capture UI is the plugin's job. Stays reachable while "
                    + "the subscription is paused, so a tenant can pay to recover.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject addPaymentMethod(@PathVariable UUID orgId, @RequestBody PaymentMethodRequest request) {
        boolean isDefault = Boolean.TRUE.equals(request.isDefault());
        UUID id = billing.addPaymentMethod(orgId, request.pluginName(), isDefault, request.pluginProperties());
        return new ResourceObject(id.toString(), "payment-method",
                new BillingResources.PaymentMethodAttributes(request.pluginName(), isDefault));
    }

    @PutMapping("/payment-methods/{paymentMethodId}/default")
    @Operation(summary = "Make a payment method the default")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    void setDefault(@PathVariable UUID orgId, @PathVariable UUID paymentMethodId) {
        billing.setDefaultPaymentMethod(orgId, paymentMethodId);
    }

    @DeleteMapping("/payment-methods/{paymentMethodId}")
    @Operation(summary = "Remove a payment method")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID orgId, @PathVariable UUID paymentMethodId) {
        billing.removePaymentMethod(orgId, paymentMethodId);
    }
}
