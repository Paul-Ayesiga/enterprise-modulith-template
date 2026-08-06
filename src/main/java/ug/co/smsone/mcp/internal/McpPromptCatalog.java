package ug.co.smsone.mcp.internal;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Curated prompts — few by design. A prompt here is a worked procedure over the tool catalog, not
 * documentation (that's the resources); each one earns its place by encoding an order of operations
 * an agent would otherwise have to discover by trial.
 */
@Component
class McpPromptCatalog {

    Map<String, McpStatelessServerFeatures.SyncPromptSpecification> specifications() {
        return Map.of(
                "diagnose_failed_webhooks", new McpStatelessServerFeatures.SyncPromptSpecification(
                        new McpSchema.Prompt("diagnose_failed_webhooks", "Diagnose failed webhooks",
                                "Walk the webhook delivery log, identify why deliveries fail, and "
                                        + "redeliver once the receiver is fixed.",
                                List.of(new McpSchema.PromptArgument("subscription_id",
                                        "Focus on one subscription (optional).", false))),
                        (context, request) -> diagnoseFailedWebhooks(request)),
                "usage_review", new McpStatelessServerFeatures.SyncPromptSpecification(
                        new McpSchema.Prompt("usage_review", "Review usage against plan",
                                "Compare recent API usage with the subscription's entitlements and "
                                        + "flag anything approaching a cap.", List.of()),
                        (context, request) -> usageReview()));
    }

    private static McpSchema.GetPromptResult diagnoseFailedWebhooks(McpSchema.GetPromptRequest request) {
        Object subscriptionId = request.arguments() == null ? null
                : request.arguments().get("subscription_id");
        String scope = subscriptionId == null
                ? "For each subscription from webhooks_list"
                : "For subscription " + subscriptionId;
        return new McpSchema.GetPromptResult("Webhook failure diagnosis procedure",
                List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent("""
                        Diagnose this organization's failing webhooks and get deliveries flowing again.

                        %s: read webhook_deliveries and group failures by responseStatus and \
                        lastError. Distinguish receiver bugs (4xx, TLS, timeouts — the tenant's \
                        endpoint is broken) from platform-side signature disputes (the receiver \
                        rejects the HMAC — usually a stale secret after a rotation). Summarize \
                        per-subscription: what is failing, since when, and the likely cause. Only \
                        after the receiver is confirmed fixed, webhook_redeliver the FAILED \
                        deliveries, oldest first, and verify they leave the FAILED state. If the \
                        secret is the suspect, propose webhook_rotate_secret and remind me the new \
                        secret must reach the receiver BEFORE rotating.""".formatted(scope)))));
    }

    private static McpSchema.GetPromptResult usageReview() {
        return new McpSchema.GetPromptResult("Usage-vs-plan review procedure",
                List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent("""
                        Review this organization's API usage against its plan.

                        Read usage_summary (30 days) and subscription_get. Report: total requests, \
                        the daily trend (growing, flat, spiky), and each entitlement with a numeric \
                        cap alongside the observed usage that counts against it. Flag anything \
                        above 80% of its cap, and say plainly if nothing is close. If usage is \
                        trending toward a cap, estimate when it would be hit at the current rate."""))));
    }
}
