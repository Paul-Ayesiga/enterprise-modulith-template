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
     * Start a signup trial for a newly registered org — {@code planCode} for {@code trialDays} — but a
     * NO-OP when the org already has a subscription, so a straight-to-paid assignment made at creation
     * is never overridden. Idempotent; the plan must be paid (FREE is rejected, as with startTrial).
     */
    void startTrialForNewOrg(UUID organizationId, String planCode, int trialDays);

    /**
     * Dunning's teeth: pause every subscription that has sat {@code PAST_DUE} longer than
     * {@code grace} — the org goes read-only until a payment lands or a plan is assigned. Idempotent
     * (a paused row is no longer PAST_DUE); returns how many were paused.
     */
    int pauseLapsedPastDue(java.time.Duration grace);

    /**
     * Flip the standing without touching the plan — {@code ACTIVE} | {@code PAST_DUE} |
     * {@code CANCELLED} (payment outcomes). Unknown org: a no-op with a log line, not an error —
     * billing events can race provisioning.
     */
    void markStatus(UUID organizationId, String status);
}
