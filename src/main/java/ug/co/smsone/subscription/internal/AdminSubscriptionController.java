package ug.co.smsone.subscription.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The platform's commercial controls: read a tenant's subscription, assign its plan, browse the
 * catalog. Assigning is {@code platform-admin} — it changes what a tenant may do — and audited.
 * Class-level {@code /api/v1/admin} mapping keeps the surface out of the X-Impersonate docs.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminSubscriptionController {

    private final SubscriptionService subscriptions;

    AdminSubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    record AssignRequest(String plan) {
    }

    record PlanAttributes(String code, String name, int rank, Map<String, Long> entitlements) {
    }

    @GetMapping("/orgs/{orgId}/subscription")
    @Operation(summary = "Inspect a tenant's subscription as the platform")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject get(@PathVariable UUID orgId) {
        return SubscriptionResources.toResource(subscriptions.view(orgId));
    }

    @PutMapping("/orgs/{orgId}/subscription")
    @Operation(summary = "Assign a tenant's plan",
            description = """
                    Upserts the subscription onto the named plan (`FREE`, `PRO`, `ENTERPRISE` as \
                    seeded) in good standing. Takes effect on the very next gate check — the \
                    entitlement cache is evicted through `SubscriptionChanged`, which also fans \
                    out `org.subscription_changed` to the tenant's webhooks. Audited.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject assign(@PathVariable UUID orgId, @RequestBody AssignRequest request) {
        return SubscriptionResources.toResource(subscriptions.assign(orgId, request.plan()));
    }

    @GetMapping("/plans")
    @Operation(summary = "List the plan catalog",
            description = "Seeded vocabulary, rank-ordered. `entitlements` uses the module's encoding: "
                    + "null value = feature on, number = cap, absent key = off/unlimited.")
    @PreAuthorize("hasRole('platform-support')")
    List<ResourceObject> plans() {
        return subscriptions.catalog().stream()
                .map(plan -> new ResourceObject(plan.getCode(), "plan",
                        new PlanAttributes(plan.getCode(), plan.getName(), plan.getRank(),
                                plan.getEntitlements())))
                .toList();
    }
}
