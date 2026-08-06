package ug.co.smsone.subscription.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.subscription.SubscriptionGate;

/** The {@link SubscriptionGate} port: the same PAUSED test {@link SubscriptionAccessFilter} applies. */
@Component
class SubscriptionGateImpl implements SubscriptionGate {

    private final OrgSubscriptionRepository subscriptions;

    SubscriptionGateImpl(OrgSubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean writesBlocked(UUID orgId) {
        return subscriptions.statusOf(orgId).orElse(null) == OrgSubscription.Status.PAUSED;
    }
}
