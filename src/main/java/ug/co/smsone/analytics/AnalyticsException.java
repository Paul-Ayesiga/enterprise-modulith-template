package ug.co.smsone.analytics;

/** Wraps engine/SQL failures; safe to log, never sent to clients verbatim. */
public class AnalyticsException extends RuntimeException {

    public AnalyticsException(String message, Throwable cause) {
        super(message, cause);
    }
}
