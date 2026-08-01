package ug.co.smsone.subscription;

import java.util.UUID;

/**
 * Write port for the entitlement authority — how the billing integration (or ops tooling) drives
 * plan state without reaching into this module. Everything lands on the SAME paths the admin
 * surface uses: assignment publishes {@code SubscriptionChanged} (cache eviction + webhook), and
 * is audited, whoever the caller is.
 */
public interface Subscriptions {

    /** Upsert the org onto the named plan in good standing (the admin PUT's exact semantics). */
    void assignPlan(UUID organizationId, String planCode);

    /**
     * Start (or restart) a paid-plan trial of {@code trialDays} (&le; 0 means the default): the org
     * runs on {@code planCode} with full access until it lapses, then the expiry job PAUSES it
     * (read-only). Rejects FREE. Lands on the same audited path as the admin trial endpoint.
     */
    void startTrial(UUID organizationId, String planCode, int trialDays);

    /**
     * Flip the standing without touching the plan — {@code ACTIVE} | {@code PAST_DUE} |
     * {@code CANCELLED} (payment outcomes). Unknown org: a no-op with a log line, not an error —
     * billing events can race provisioning.
     */
    void markStatus(UUID organizationId, String status);
}
