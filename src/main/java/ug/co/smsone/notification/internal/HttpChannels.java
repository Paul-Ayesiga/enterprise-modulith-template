package ug.co.smsone.notification.internal;

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

/** Minimal JSON HTTP POST used by the webhook and Slack channels (no Jackson on this path). */
final class HttpChannels {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NEVER is the JDK default, but here it is load-bearing: following a redirect would fetch
            // a location SafeOutboundUrl never validated (302 → http://169.254.169.254/…).
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpChannels() {
    }

    static void postJson(String url, String json, int timeoutSeconds) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        // Bound the WHOLE exchange. HttpRequest.timeout() only covers time-to-headers, so a receiver
        // that returns headers then holds the body open would otherwise hang the sending thread —
        // and one hung send stalls the delivery worker's whole batch. get(timeout) (NOT orTimeout,
        // which completes the future and makes cancel a no-op) leaves the future pending on timeout,
        // so cancel(true) actually aborts the underlying connection instead of leaking it.
        CompletableFuture<HttpResponse<Void>> exchange =
                CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        HttpResponse<Void> response;
        try {
            response = exchange.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            exchange.cancel(true);
            throw new NotificationDeliveryException(
                    "POST to " + url + " timed out after " + timeoutSeconds + "s", ex);
        } catch (InterruptedException ex) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new NotificationDeliveryException("POST to " + url + " was interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new NotificationDeliveryException("POST to " + url + " failed: " + cause.getMessage(), cause);
        }
        if (response.statusCode() >= 300) {
            // 3xx/4xx are the receiver's contract (bad URL, auth, gone) — retrying cannot help.
            // 408 (timeout) and 429 (throttled) are the transient exceptions; 5xx is retryable.
            boolean permanent = response.statusCode() < 500
                    && response.statusCode() != 408 && response.statusCode() != 429;
            throw new NotificationDeliveryException(
                    "HTTP " + response.statusCode() + " from " + url, permanent);
        }
    }

    /** Escape a string as a JSON string literal (including surrounding quotes). */
    static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
