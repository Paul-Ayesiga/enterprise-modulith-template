package ug.co.smsone.notification.internal;

/** Raised by a channel sender when delivery fails. Recorded server-side only, never on the wire. */
class NotificationDeliveryException extends RuntimeException {

    NotificationDeliveryException(String message) {
        super(message);
    }

    NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
