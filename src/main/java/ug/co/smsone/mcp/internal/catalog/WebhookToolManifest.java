package ug.co.smsone.mcp.internal.catalog;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.mcp.internal.ToolDefinition;
import ug.co.smsone.mcp.internal.ToolDefinition.Kind;
import ug.co.smsone.mcp.internal.ToolManifest;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.webhooks.WebhookAdmin;

/**
 * Webhook tools — the full management surface plus the two an agent diagnosing an integration
 * actually lives in: the delivery log and redelivery. One REST gate ({@code webhook:manage})
 * covers all of it, mirrored here; the SSRF guard and secret-shown-once rules ride the port.
 *
 * <p>The one asymmetry an agent cannot infer from the permission alone: a deleted subscription's
 * delivery log stays READABLE (until retention purges it) while redelivery on it does not, because
 * sending to a revoked endpoint would resurrect traffic its owner turned off. Both descriptions say
 * so — "eligible for webhook_redeliver" unqualified read as a promise the service refuses.
 */
@Component
class WebhookToolManifest implements ToolManifest {

    private final WebhookAdmin webhooks;

    WebhookToolManifest(WebhookAdmin webhooks) {
        this.webhooks = webhooks;
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ToolDefinition("webhooks_list", "List webhook subscriptions",
                        "List this organization's webhook subscriptions (URL, subscribed events, "
                                + "status). Secrets are always masked here.",
                        1, "webhooks", Kind.READ, "webhook:manage",
                        ToolArgs.schema(ToolArgs.pageProperties()),
                        (context, args) -> webhooks.list(context.orgId(), ToolArgs.page(args))),

                new ToolDefinition("webhook_get", "Get webhook subscription",
                        "Read one webhook subscription. The secret is masked — it is shown in full "
                                + "only by webhook_create and webhook_rotate_secret.",
                        1, "webhooks", Kind.READ, "webhook:manage",
                        ToolArgs.schema(Map.of("subscription_id",
                                ToolArgs.string("The subscription id.")), "subscription_id"),
                        (context, args) -> webhooks.get(context.orgId(), id(args, "subscription_id"))),

                new ToolDefinition("webhook_create", "Register webhook",
                        "Register a webhook endpoint for event codes. SENSITIVE RESULT: the signing "
                                + "secret is returned in full exactly once, in this result — hand it "
                                + "to the receiver's configuration immediately and do not repeat it "
                                + "in conversation. Private/internal URLs are refused.",
                        1, "webhooks", Kind.WRITE, "webhook:manage",
                        ToolArgs.schema(createProperties(), "url", "events"),
                        (context, args) -> webhooks.create(context.orgId(),
                                ToolArgs.requireString(args, "url"), events(args))),

                new ToolDefinition("webhook_update", "Update webhook",
                        "Replace a subscription's URL and event list wholesale (not merged). "
                                + "Omitting status resets it to ACTIVE.",
                        1, "webhooks", Kind.WRITE, "webhook:manage",
                        ToolArgs.schema(updateProperties(), "subscription_id", "url", "events"),
                        (context, args) -> webhooks.update(context.orgId(), id(args, "subscription_id"),
                                ToolArgs.requireString(args, "url"), events(args),
                                ToolArgs.optionalString(args, "status"))),

                new ToolDefinition("webhook_rotate_secret", "Rotate webhook secret",
                        "Mint a replacement signing secret — the old one stops verifying immediately, "
                                + "so deploy the new one to the receiver first. SENSITIVE RESULT: the "
                                + "new secret is returned in full exactly once.",
                        1, "webhooks", Kind.WRITE, "webhook:manage",
                        ToolArgs.schema(Map.of("subscription_id",
                                ToolArgs.string("The subscription id.")), "subscription_id"),
                        (context, args) -> webhooks.rotateSecret(context.orgId(), id(args, "subscription_id"))),

                new ToolDefinition("webhook_delete", "Delete webhook",
                        "Delete a subscription: stops matching new events and cancels queued "
                                + "deliveries. The delivery log survives and stays readable.",
                        1, "webhooks", Kind.DESTRUCTIVE, "webhook:manage",
                        ToolArgs.schema(Map.of("subscription_id",
                                ToolArgs.string("The subscription id.")), "subscription_id"),
                        (context, args) -> {
                            UUID id = id(args, "subscription_id");
                            webhooks.delete(context.orgId(), id);
                            return Map.of("deleted", id.toString());
                        }),

                new ToolDefinition("webhook_deliveries", "Delivery log",
                        "A subscription's delivery attempts, newest first: attempts vs maxAttempts, "
                                + "the receiver's responseStatus, and lastError for failures. Readable "
                                + "even after the subscription is deleted — but that is READ only: "
                                + "FAILED rows are dead-lettered and eligible for webhook_redeliver "
                                + "while the subscription is LIVE, and a deleted one's are not.",
                        1, "webhooks", Kind.READ, "webhook:manage",
                        ToolArgs.schema(deliveriesProperties(), "subscription_id"),
                        (context, args) -> webhooks.deliveries(context.orgId(),
                                id(args, "subscription_id"), ToolArgs.page(args))),

                new ToolDefinition("webhook_redeliver", "Redeliver failed delivery",
                        "Re-queue one FAILED (dead-lettered) delivery for a fresh retry cycle — after "
                                + "fixing the receiver. Only FAILED deliveries qualify; anything else "
                                + "answers a conflict. The subscription must still exist: its log "
                                + "outlives deletion but redelivery does not, because sending to a "
                                + "deleted endpoint would resurrect traffic its owner revoked.",
                        1, "webhooks", Kind.WRITE, "webhook:manage",
                        ToolArgs.schema(Map.of(
                                        "subscription_id", ToolArgs.string("The subscription id."),
                                        "delivery_id", ToolArgs.string("The FAILED delivery's id.")),
                                "subscription_id", "delivery_id"),
                        (context, args) -> {
                            UUID deliveryId = id(args, "delivery_id");
                            webhooks.redeliver(context.orgId(), id(args, "subscription_id"), deliveryId);
                            return Map.of("requeued", deliveryId.toString());
                        }));
    }

    private static UUID id(Map<String, Object> args, String name) {
        try {
            return UUID.fromString(ToolArgs.requireString(args, name));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("'" + name + "' must be a UUID.", ApiSource.pointer("/" + name));
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> events(Map<String, Object> args) {
        Object value = args.get("events");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new ValidationException("'events' must be a non-empty array of event codes.",
                    ApiSource.pointer("/events"));
        }
        return new LinkedHashSet<>((List<String>) list);
    }

    private static Map<String, Object> createProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", ToolArgs.string("The receiver URL (public; private hosts are refused)."));
        properties.put("events", Map.of("type", "array", "description",
                "Event codes to subscribe to.", "items", Map.of("type", "string"), "minItems", 1));
        return properties;
    }

    private static Map<String, Object> updateProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subscription_id", ToolArgs.string("The subscription id."));
        properties.putAll(createProperties());
        properties.put("status", ToolArgs.string("ACTIVE or DISABLED (omitted = ACTIVE)."));
        return properties;
    }

    private static Map<String, Object> deliveriesProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subscription_id", ToolArgs.string("The subscription id."));
        properties.putAll(ToolArgs.pageProperties());
        return properties;
    }
}
