package ug.co.smsone.mcp.internal.catalog;

import java.util.List;
import org.springframework.stereotype.Component;
import ug.co.smsone.billing.UsageSummaries;
import ug.co.smsone.mcp.internal.ToolDefinition;
import ug.co.smsone.mcp.internal.ToolDefinition.Kind;
import ug.co.smsone.mcp.internal.ToolManifest;
import ug.co.smsone.subscription.SubscriptionOverview;

/**
 * Standing tools — subscription and usage: "what plan am I on, what am I entitled to, how much have
 * I used". Reads only; plan changes are billing-driven and payment writes are a deliberate MCP
 * non-goal (plan §8).
 */
@Component
class StandingToolManifest implements ToolManifest {

    private final SubscriptionOverview subscriptions;
    private final UsageSummaries usage;

    StandingToolManifest(SubscriptionOverview subscriptions, UsageSummaries usage) {
        this.subscriptions = subscriptions;
        this.usage = usage;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ToolDefinition("subscription_get", "Get subscription",
                        "Read this organization's subscription: plan, status, trial/period dates, and "
                                + "entitlements (null value = feature on, number = cap, absent = "
                                + "off/unlimited). No subscription row means the seeded FREE plan.",
                        1, "subscription", Kind.READ, "subscription:read",
                        ToolDefinition.noArguments(),
                        (context, args) -> subscriptions.of(context.orgId())),

                new ToolDefinition("usage_summary", "Usage summary",
                        "This organization's API usage from the platform ledger: total and per-day "
                                + "request counts over a trailing window (UTC days, as metered at the "
                                + "edge).",
                        1, "billing", Kind.READ, "usage:read",
                        ToolArgs.schema(java.util.Map.of(
                                "days", ToolArgs.integer("Trailing window in days (default 30).", 1, 90))),
                        (context, args) -> usage.lastDays(context.orgId(),
                                ToolArgs.intOr(args, "days", 30))));
    }
}
