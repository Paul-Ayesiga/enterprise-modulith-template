package ug.co.smsone.subscription;

import java.util.UUID;

/**
 * Read-side port answering "may this org write right now?" for surfaces outside the {@code /api/**}
 * filter chain (the MCP dispatcher today). PAUSED — a trial that lapsed unpaid — means read-only,
 * exactly the decision {@code SubscriptionAccessFilter} enforces for REST; one authority, two
 * surfaces.
 */
public interface SubscriptionGate {

    /** True when the org's subscription is PAUSED and writes must refuse. No subscription = open. */
    boolean writesBlocked(UUID orgId);
}
