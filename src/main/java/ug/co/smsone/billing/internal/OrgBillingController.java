package ug.co.smsone.billing.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/** The tenant's own money view: their invoices. Changing plans is the platform's surface. */
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
}
