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
        String resolved = resolver.resolve(key, LocaleContextHolder.getLocale());
        return key.equals(resolved) ? fallbackDetail : resolved;
    }
}
