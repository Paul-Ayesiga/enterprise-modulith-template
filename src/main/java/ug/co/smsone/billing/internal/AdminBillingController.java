package ug.co.smsone.billing.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The platform's billing controls over Kill Bill: provision a tenant's billing account, read its
 * money state, put it on a billable plan, browse its invoices. Entitlements still move only
 * through the subscription module — this surface drives Kill Bill, and Kill Bill's state
 * reconciles back. Class-level {@code /api/v1/admin} mapping keeps it out of the X-Impersonate docs.
 *
 * <p><strong>Every route here enters the tenant explicitly, and that is not optional.</strong>
 * {@code /api/v1/admin/orgs/{orgId}/…} is deliberately NOT matched by {@code CurrentUserFilter}'s
 * org-scoped pattern — the caller is a platform operator who has no organization and never will — so
 * the request arrives on the PLATFORM axis. Both tables behind this surface are TENANT-tier:
 * {@code billing_account} is the org's Kill Bill linkage and {@code org_subscription} is what
 * {@code reconcile} writes through the subscription port (ADR 0010 §2). This is the cross-tenant
 * admin write ADR 0010 §5.15 describes, and {@link TenantContext#runAs} is how it is supposed to
 * read at the call site: a deliberate capability, spelled out.
 *
 * <p>The pin wraps the Kill Bill round trips too. That costs nothing — a pin is a thread-local, not a
 * connection — and splitting it to exclude the remote calls would only add spans that have to be
 * re-entered for the row write on the other side.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminBillingController {

    private final BillingService billing;

    AdminBillingController(BillingService billing) {
        this.billing = billing;
    }

    record SubscribeRequest(String plan) {
    }

    record AccountAttributes(String kbAccountId) {
    }

    @PostMapping("/orgs/{orgId}/billing/account")
    @Operation(summary = "Provision a tenant's Kill Bill account",
            description = """
                    Creates (or resolves — idempotent by `externalKey` = org id) the Kill Bill \
                    account and records the linkage. A tenant needs one before any billable plan.""")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject provision(@PathVariable UUID orgId) {
        BillingAccount account = TenantContext.callAs(orgId, () -> billing.provision(orgId));
        return new ResourceObject(orgId.toString(), "billing-account",
                new AccountAttributes(account.getKbAccountId().toString()));
    }

    @GetMapping("/orgs/{orgId}/billing")
    @Operation(summary = "Inspect a tenant's billing state",
            description = "Balance and live Kill Bill subscriptions, read straight from Kill Bill.")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject view(@PathVariable UUID orgId) {
        return BillingResources.toResource(TenantContext.callAs(orgId, () -> billing.view(orgId)));
    }

    @PostMapping("/orgs/{orgId}/billing/subscription")
    @Operation(summary = "Put a tenant on a billable plan",
            description = """
                    Creates the Kill Bill subscription for the mapped catalog plan and reconciles \
                    entitlements immediately (the push notification converges the same way). \
                    `FREE` is not billable — assign it through the subscription surface instead.""")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResourceObject subscribe(@PathVariable UUID orgId, @RequestBody SubscribeRequest request) {
        return BillingResources.toResource(TenantContext.callAs(orgId, () -> {
            billing.subscribe(orgId, request.plan());
            return billing.view(orgId);
        }));
    }

    @GetMapping("/orgs/{orgId}/billing/invoices")
    @Operation(summary = "List a tenant's invoices",
            description = "Proxied from Kill Bill, un-paged — the billing UI of record is Kaui.")
    @PreAuthorize("hasRole('platform-support')")
    List<ResourceObject> invoices(@PathVariable UUID orgId) {
        return BillingResources.toInvoiceResources(TenantContext.callAs(orgId, () -> billing.invoices(orgId)));
    }
}
