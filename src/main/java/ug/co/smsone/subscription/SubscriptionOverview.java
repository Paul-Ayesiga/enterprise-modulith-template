package ug.co.smsone.subscription;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read port over an org's subscription standing for other protocol surfaces (the MCP module
 * today) — the same view the REST subscription endpoint renders: no row means the seeded FREE
 * plan; {@code entitlements}: null value = feature on, number = cap, absent key = off/unlimited.
 */
public interface SubscriptionOverview {

    SubscriptionInfo of(UUID orgId);

    record SubscriptionInfo(UUID orgId, String planCode, String planName, String status,
            Instant currentPeriodEnd, Instant trialEndsAt, Map<String, Long> entitlements) {
    }
}
