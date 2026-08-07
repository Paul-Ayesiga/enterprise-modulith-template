package ug.co.smsone.notification.internal;

import java.io.Serial;

/**
 * Raised by a channel sender when delivery fails. Recorded server-side only, never on the wire.
 * {@code permanent} failures (e.g. HTTP 4xx from a webhook) are dead-lettered immediately —
 * retrying a request the receiver rejects by contract only burns attempts and provider quota.
 */
class NotificationDeliveryException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean permanent;

    NotificationDeliveryException(String message) {
        this(message, false);
    }

    NotificationDeliveryException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
        this.permanent = false;
    }

    boolean permanent() {
        return permanent;
    }
}
