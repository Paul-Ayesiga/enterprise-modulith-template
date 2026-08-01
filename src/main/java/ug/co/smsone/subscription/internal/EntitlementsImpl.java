package ug.co.smsone.subscription.internal;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.subscription.Entitlements;

/** The gate itself; refusals are curated toward "upgrade", never toward "permission bug". */
@Service
class EntitlementsImpl implements Entitlements {

    private final EntitlementResolver resolver;

    EntitlementsImpl(EntitlementResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean hasFeature(UUID organizationId, String featureKey) {
        return resolver.resolve(organizationId).containsKey(featureKey);
    }

    @Override
    public Long limitOf(UUID organizationId, String limitKey) {
        Map<String, Long> entitlements = resolver.resolve(organizationId);
        Long value = entitlements.get(limitKey);
        return value == null || value == EntitlementResolver.FEATURE ? null : value;
    }

    @Override
    public void requireFeature(UUID organizationId, String featureKey) {
        if (!hasFeature(organizationId, featureKey)) {
            throw new ForbiddenException("Your organization's plan does not include '" + featureKey
                    + "'. Upgrade the plan to use this feature.");
        }
    }

    @Override
    public void requireWithinLimit(UUID organizationId, String limitKey, long currentCount) {
        Long limit = limitOf(organizationId, limitKey);
        if (limit != null && currentCount >= limit) {
            throw new ForbiddenException("Your organization's plan allows " + limit + " for '"
                    + limitKey + "' and that limit is reached. Upgrade the plan to add more.");
        }
    }
}
