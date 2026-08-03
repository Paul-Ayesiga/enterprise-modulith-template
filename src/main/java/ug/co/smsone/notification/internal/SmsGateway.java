package ug.co.smsone.notification.internal;

import java.util.Map;
import ug.co.smsone.notification.NotificationMessage;

/**
 * One SMS provider adapter. The router resolves WHICH provider serves the recipient's org (the
 * integration hub's choice) and hands the adapter that integration's decrypted {@code settings};
 * an empty map means "no hub entry — use your env fallback". Adding a provider = adding a bean.
 */
interface SmsGateway {

    /** Matches the integration hub's {@code provider} value (e.g. {@code speedamobile}). */
    String provider();

    void send(NotificationMessage message, Map<String, String> settings);
}
