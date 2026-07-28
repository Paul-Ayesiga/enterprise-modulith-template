package ug.co.smsone.webhooks.internal;

/**
 * A failed webhook send. {@code permanent} failures (4xx contract rejections, an unsafe URL) are
 * dead-lettered immediately; transient ones (5xx, timeout, DNS blip) are retried with backoff.
 * {@code responseStatus} is the receiver's HTTP status when there was one, else {@code null}.
 */
class WebhookDeliveryException extends RuntimeException {

    private final boolean permanent;
    private final Integer responseStatus;

    WebhookDeliveryException(String message, boolean permanent, Integer responseStatus) {
        super(message);
        this.permanent = permanent;
        this.responseStatus = responseStatus;
    }

    WebhookDeliveryException(String message, Throwable cause) {
        super(message, cause);
        this.permanent = false;
        this.responseStatus = null;
    }

    boolean permanent() {
        return permanent;
    }

    Integer responseStatus() {
        return responseStatus;
    }
}
