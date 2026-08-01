package ug.co.smsone.subscription.internal;

import java.time.Instant;
import java.util.Map;
import ug.co.smsone.shared.web.ResourceObject;

/** One resource shape for both surfaces — the platform and the tenant read the same truth. */
final class SubscriptionResources {

    static final String RESOURCE_TYPE = "subscription";

    record SubscriptionAttributes(String planCode, String planName, String status,
            Instant currentPeriodEnd, Instant trialEndsAt, Map<String, Long> entitlements) {
    }

    private SubscriptionResources() {
    }

    static ResourceObject toResource(SubscriptionService.SubscriptionView view) {
        return new ResourceObject(view.orgId().toString(), RESOURCE_TYPE,
                new SubscriptionAttributes(view.planCode(), view.planName(), view.status(),
                        view.currentPeriodEnd(), view.trialEndsAt(), view.entitlements()));
    }
}
