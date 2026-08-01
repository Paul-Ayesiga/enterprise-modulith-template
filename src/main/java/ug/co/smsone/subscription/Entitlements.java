package ug.co.smsone.subscription;

import java.util.UUID;

/**
 * What an organization's plan allows — the port consuming modules gate on, resolved from the org's
 * subscription (or FREE when none) and cached per org. Encoding: a feature key PRESENT on the plan
 * is enabled; a limit key present caps a count; an ABSENT key means feature-off for
 * {@code requireFeature} and UNLIMITED for {@code requireWithinLimit} — so the top plan simply
 * carries no limit rows. The {@code require*} forms throw a curated 403 naming the plan and the
 * limit, so a gate refusal reads as "upgrade", never as a permission bug.
 */
public interface Entitlements {

    boolean hasFeature(UUID organizationId, String featureKey);

    /** The numeric cap for {@code limitKey}, or null when the plan leaves it unlimited. */
    Long limitOf(UUID organizationId, String limitKey);

    void requireFeature(UUID organizationId, String featureKey);

    /** Refuses (403) when {@code currentCount} has already reached the plan's cap. */
    void requireWithinLimit(UUID organizationId, String limitKey, long currentCount);
}
