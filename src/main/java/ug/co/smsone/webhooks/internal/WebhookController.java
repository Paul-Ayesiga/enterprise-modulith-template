package ug.co.smsone.webhooks.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Window;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Per-org webhook subscriptions + their delivery log. Org-scoped via {@code webhook:manage}. The signing
 * secret is returned in full only on create; every other read masks it.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/webhooks")
class WebhookController {

    private static final String RESOURCE_TYPE = "webhook";
    private static final String DELIVERY_TYPE = "webhook-delivery";

    private final WebhookSubscriptionService service;

    WebhookController(WebhookSubscriptionService service) {
        this.service = service;
    }

    record WebhookAttributes(String url, Set<String> events, String status, String secret) {
    }

    record DeliveryAttributes(String eventType, String status, int attempts, int maxAttempts,
            Integer responseStatus, String lastError) {
    }

    record CreateWebhookRequest(@NotBlank String url, @NotEmpty Set<String> events) {
    }

    record UpdateWebhookRequest(@NotBlank String url, @NotEmpty Set<String> events, String status) {
    }

    @PostMapping
    @Operation(summary = "Register a webhook subscription",
            description = """
                    The signing secret is returned in full exactly once — in this response. Every \
                    later read masks it and there is no way to retrieve it again, so store it now: it \
                    is the key that verifies the `X-Webhook-Signature` HMAC on delivered events.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'webhook:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject create(@PathVariable UUID orgId, @Valid @RequestBody CreateWebhookRequest request) {
        WebhookSubscriptionService.CreatedSubscription created =
                service.create(orgId, request.url(), request.events());
        // Full secret, shown once — from the create result; the ROW only ever holds the ciphertext.
        return toResource(created.subscription(), created.plainSecret());
    }

    @GetMapping
    @Operation(summary = "List the organization's webhook subscriptions")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'webhook:manage')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(service.list(orgId, page), page,
                subscription -> toResource(subscription, mask(subscription.getSecret())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one webhook subscription",
            description = "The `secret` attribute is masked here — it is shown in full only at create.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'webhook:manage')")
    ResourceObject get(@PathVariable UUID orgId, @PathVariable UUID id) {
        WebhookSubscription subscription = service.require(orgId, id);
        return toResource(subscription, mask(subscription.getSecret()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a webhook subscription",
            description = """
                    `url` and `events` are replaced wholesale, not merged. Omitting `status` resets \
                    the subscription to ACTIVE, so a disabled subscription silently resumes delivery \
                    unless the update repeats `status: DISABLED`. The signing secret is never rotated \
                    here.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'webhook:manage')")
    ResourceObject update(@PathVariable UUID orgId, @PathVariable UUID id,
            @Valid @RequestBody UpdateWebhookRequest request) {
        WebhookSubscription updated = service.update(orgId, id, request.url(), request.events(),
                parseStatus(request.status()));
        return toResource(updated, mask(updated.getSecret()));
    }

    @PostMapping("/{id}/rotate-secret")
    @Operation(summary = "Rotate the signing secret",
            description = """
                    Mints a replacement secret, returned in full exactly once — the old secret stops \
                    verifying immediately. Deploy the new secret to your receiver BEFORE rotating, \
                    or deliveries in between will fail signature checks (they retry with backoff, so \
                    a quick swap loses nothing).""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'webhook:manage')")
    ResourceObject rotateSecret(@PathVariable UUID orgId, @PathVariable UUID id) {
        WebhookSubscriptionService.CreatedSubscription rotated = service.rotateSecret(orgId, id);
        return toResource(rotated.subscription(), rotated.plainSecret());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a webhook subscription",
            description = """
                    Stops matching new events and cancels everything already queued for the endpoint, \
                    so a revoked URL is not retried for hours. The delivery log survives and stays \
                    readable — "what did we send that endpoint?" is exactly the question asked after \
                    a delete.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'webhook:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        service.delete(orgId, id);
    }

    @GetMapping("/{id}/deliveries")
    @Operation(summary = "List a subscription's delivery attempts",
            description = """
                    The delivery log for one subscription, newest first: `attempts` against \
                    `maxAttempts`, the receiver's `responseStatus`, and `lastError` for the failures. \
                    Readable after the subscription itself is deleted, which is when it is most \
                    often wanted.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'webhook:manage')")
    WindowedResult<ResourceObject> deliveries(@PathVariable UUID orgId, @PathVariable UUID id,
            CursorPageRequest page) {
        Window<WebhookDelivery> window = service.deliveries(orgId, id, page);
        return WindowedResult.of(window, page, WebhookController::toDeliveryResource);
    }

    private static SubscriptionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return SubscriptionStatus.ACTIVE;
        }
        try {
            return SubscriptionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("status must be ACTIVE or DISABLED.",
                    ApiSource.pointer("/data/attributes/status"));
        }
    }

    private static String mask(String secret) {
        // Reveal none of the random secret — only the (non-secret) scheme prefix every minted
        // secret carries. The stored form is now ciphertext (enc:v1:…), so the mask is a constant
        // rather than derived: it describes the PLAINTEXT the tenant holds, not the row.
        return "whsec_••••••";
    }

    private static ResourceObject toResource(WebhookSubscription subscription, String secret) {
        return new ResourceObject(subscription.getId().toString(), RESOURCE_TYPE,
                new WebhookAttributes(subscription.getUrl(), subscription.eventTypeSet(),
                        subscription.getStatus().name(), secret));
    }

    private static ResourceObject toDeliveryResource(WebhookDelivery delivery) {
        return new ResourceObject(delivery.getId().toString(), DELIVERY_TYPE,
                new DeliveryAttributes(delivery.getEventType(), delivery.getStatus(), delivery.getAttempts(),
                        delivery.getMaxAttempts(), delivery.getResponseStatus(), delivery.getLastError()));
    }
}
