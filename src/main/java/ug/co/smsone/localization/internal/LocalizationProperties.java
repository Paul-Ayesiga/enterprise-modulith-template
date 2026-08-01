package ug.co.smsone.localization.internal;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Localization policy. The default locale is the fallback chain's floor, so a tag Java cannot
 * parse must fail at startup — a silent {@code und} default would make every fallback miss.
 */
@ConfigurationProperties(prefix = "app.localization")
record LocalizationProperties(String defaultLocale) {

    LocalizationProperties {
        defaultLocale = defaultLocale == null || defaultLocale.isBlank()
                ? "en"
                : defaultLocale.trim().toLowerCase(Locale.ROOT);
        if (Locale.forLanguageTag(defaultLocale).getLanguage().isEmpty()) {
            throw new IllegalArgumentException(
                    "app.localization.default-locale must be a valid BCP-47 tag: " + defaultLocale);
        }
    }
}
