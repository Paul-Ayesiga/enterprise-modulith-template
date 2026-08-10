package ug.co.smsone.shared.error;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.i18n.Messages;

/**
 * Localizes an error response's {@code detail} through the translation catalog, keyed
 * {@code error.<code>} (e.g. {@code error.rate_limited}) and resolved against the request's
 * {@code Accept-Language} (Spring's {@code LocaleContextHolder}). Catalog-driven and additive: a
 * key with no translation keeps the author's English detail — exactly the {@code Messages}
 * fallback contract, so localization can never turn an error into a different error.
 */
@Component
public class ErrorDetailLocalizer {

    private final ObjectProvider<Messages> messages;

    public ErrorDetailLocalizer(ObjectProvider<Messages> messages) {
        this.messages = messages;
    }

    public String localize(ErrorCode errorCode, String fallbackDetail) {
        Messages resolver = messages.getIfAvailable();
        if (resolver == null || fallbackDetail == null) {
            return fallbackDetail;
        }
        String key = "error." + errorCode.code().toLowerCase();
        try {
            String resolved = resolver.resolve(key, LocaleContextHolder.getLocale());
            return key.equals(resolved) ? fallbackDetail : resolved;
        } catch (RuntimeException lookupFailure) {
            // The class promise, kept on the path that tests forgot: the bundle is a PLATFORM read
            // (platform.translation, cached 60 s), and this method runs while rendering ERRORS —
            // including the 503 that says the platform database is unreachable (ADR 0011 §2). A cold
            // bundle during that outage made the renderer itself throw out of the FILTER, turning a
            // curated 503 + Retry-After into an unenveloped 500 — measured in the Phase 7 gate. §2.1's
            // row for translations is "last value or default"; the author's English detail IS the
            // default, so it is served and the lookup failure is a log line, never a second error.
            log.warn("error-detail localization for {} failed ({}); serving the author's default",
                    key, lookupFailure.toString());
            return fallbackDetail;
        }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ErrorDetailLocalizer.class);
}
