package ug.co.smsone.webhooks.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.http.SafeOutboundUrl;
import ug.co.smsone.shared.http.UnsafeOutboundUrlException;

/**
 * Signs and POSTs one delivery. SSRF-guards the (caller-supplied) URL, HMAC-signs the body, and bounds
 * the WHOLE exchange (not just time-to-headers) so a receiver that stalls the body can't pin the worker.
 */
@Component
class WebhookSender {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NEVER is the JDK default, but here it is load-bearing: following a redirect would fetch
            // a location SafeOutboundUrl never validated (302 → http://169.254.169.254/…).
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final WebhookProperties properties;

    private final SecretCipher cipher;

    WebhookSender(WebhookProperties properties, SecretCipher cipher) {
        this.properties = properties;
        this.cipher = cipher;
    }

    /** @return the receiver's HTTP status on success (2xx); throws {@link WebhookDeliveryException} otherwise */
    int send(ClaimedWebhookDelivery delivery) {
        guardTarget(delivery.url());
        String signature = WebhookSigner.sign(cipher.decrypt(delivery.secret()), delivery.payload());
        int timeout = properties.timeoutSeconds();
        HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.url()))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .header("X-Webhook-Event", delivery.eventType())
                .header("X-Webhook-Delivery", delivery.id().toString())
                .header("X-Webhook-Signature", "sha256=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(delivery.payload(), StandardCharsets.UTF_8))
                .build();

        CompletableFuture<HttpResponse<Void>> exchange =
                CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        HttpResponse<Void> response;
        try {
            response = exchange.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            exchange.cancel(true);
            throw new WebhookDeliveryException("POST to " + delivery.url() + " timed out after " + timeout + "s", ex);
        } catch (InterruptedException ex) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new WebhookDeliveryException("POST to " + delivery.url() + " was interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new WebhookDeliveryException("POST to " + delivery.url() + " failed: " + cause.getMessage(), cause);
        }

        int status = response.statusCode();
        if (status >= 300) {
            // 4xx are the receiver's contract (retrying can't help); 408/429/5xx are transient.
            boolean permanent = status < 500 && status != 408 && status != 429;
            throw new WebhookDeliveryException("HTTP " + status + " from " + delivery.url(), permanent, status);
        }
        return status;
    }

    private void guardTarget(String url) {
        try {
            SafeOutboundUrl.requireSafe(url, properties.allowPrivateHosts());
        } catch (UnsafeOutboundUrlException ex) {
            throw new WebhookDeliveryException(ex.getMessage(), !ex.retryable(), null);
        }
    }
}
