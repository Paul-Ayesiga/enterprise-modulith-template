package ug.co.smsone.analytics;

import java.io.Serial;

/** Wraps engine/SQL failures; safe to log, never sent to clients verbatim. */
public class AnalyticsException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AnalyticsException(String message, Throwable cause) {
        super(message, cause);
    }
}
