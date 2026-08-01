package ug.co.smsone.localization;

import java.util.Locale;

/**
 * Resolve a message key for a locale. The fallback chain is the port's contract: exact tag →
 * language only → the configured default locale → the key itself. The last step is deliberate —
 * a missing translation must render as its key, never throw: a gap in the catalog is a content
 * problem, not an outage.
 */
public interface Messages {

    /**
     * The resolved text, with {@code args} applied via {@link java.text.MessageFormat} when
     * present. A null {@code locale} resolves against the default locale.
     */
    String resolve(String key, Locale locale, Object... args);
}
