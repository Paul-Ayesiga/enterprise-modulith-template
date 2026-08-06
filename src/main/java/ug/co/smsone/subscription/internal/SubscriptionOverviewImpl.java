package ug.co.smsone.subscription.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.subscription.SubscriptionOverview;

/** The {@link SubscriptionOverview} port: the same view the REST endpoint renders. */
@Component
class SubscriptionOverviewImpl implements SubscriptionOverview {

    private final SubscriptionService subscriptions;

    SubscriptionOverviewImpl(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionInfo of(UUID orgId) {
        SubscriptionService.SubscriptionView view = subscriptions.view(orgId);
        return new SubscriptionInfo(view.orgId(), view.planCode(), view.planName(), view.status(),
                view.currentPeriodEnd(), view.trialEndsAt(), view.entitlements());
    }
}
