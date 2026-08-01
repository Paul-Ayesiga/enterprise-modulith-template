package ug.co.smsone.subscription;

/**
 * The entitlement vocabulary, in one place so a consumer and the seeded plans cannot drift apart
 * on a typo. Limits count LIVE rows; features are on/off.
 */
public final class EntitlementKeys {

    /** Cap on active members per organization. */
    public static final String MEMBERS_MAX = "members.max";
    /** Cap on webhook subscriptions per organization. */
    public static final String WEBHOOKS_MAX = "webhooks.max";
    /** Whether the exchange platform (imports/exports) is available at all. */
    public static final String EXCHANGE_ENABLED = "exchange.enabled";
    /** Cap on recurring exchange schedules per organization. */
    public static final String EXCHANGE_SCHEDULES_MAX = "exchange.schedules.max";

    private EntitlementKeys() {
    }
}
