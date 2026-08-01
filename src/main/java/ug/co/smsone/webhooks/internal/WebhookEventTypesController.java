package ug.co.smsone.webhooks.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The subscribable event vocabulary, on the wire at last — the codes the subscription endpoints
 * accept, with what each means. Global and read-only, like the permission and exchange-handler
 * catalogs: available to any authenticated caller.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookEventTypesController {

    static final String RESOURCE_TYPE = "webhook-event-type";

    record EventTypeAttributes(String code, String description) {
    }

    @GetMapping("/event-types")
    @Operation(summary = "List every subscribable webhook event type",
            description = """
                    The fixed vocabulary the subscription endpoints' `events` array accepts. \
                    Payloads always carry `event`, `orgId` and `occurredAt`, signed \
                    HMAC-SHA256 in `X-Webhook-Signature`.""")
    List<ResourceObject> list() {
        return Arrays.stream(WebhookEventType.values())
                .map(type -> new ResourceObject(type.code(), RESOURCE_TYPE,
                        new EventTypeAttributes(type.code(), type.description())))
                .toList();
    }
}
