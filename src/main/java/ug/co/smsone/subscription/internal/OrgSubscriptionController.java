package ug.co.smsone.subscription.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/** The tenant's own view: which plan, what it allows. Changing plans is the platform's surface. */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/subscription")
class OrgSubscriptionController {

    private final SubscriptionService subscriptions;

    OrgSubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping
    @Operation(summary = "Read the organization's subscription and entitlements",
            description = "No subscription row means the seeded FREE plan. `entitlements`: null "
                    + "value = feature on, number = cap, absent key = off/unlimited.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'subscription:read')")
    ResourceObject get(@PathVariable UUID orgId) {
        return SubscriptionResources.toResource(subscriptions.view(orgId));
    }
}
