package ug.co.smsone.localization.internal;

import java.text.MessageFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.i18n.Messages;

/**
 * The {@link Messages} port: walks the fallback chain over cached bundles. At most three bundle
 * lookups per resolve (exact, language, default), each an L1 hit after the first touch of a locale.
 */
@Component
class MessagesImpl implements Messages {

    private final TranslationBundles bundles;
    private final LocalizationProperties properties;

    MessagesImpl(TranslationBundles bundles, LocalizationProperties properties) {
        this.bundles = bundles;
        this.properties = properties;
    }

    @Override
    public String resolve(String key, Locale locale, Object... args) {
        String text = lookup(key, locale);
        if (text == null) {
            text = key; // the contract's last step: a catalog gap renders, it never throws
        }
        if (args.length == 0) {
            return text;
        }
        try {
            return new MessageFormat(text, locale == null
                    ? Locale.forLanguageTag(properties.defaultLocale())
                    : locale).format(args);
        } catch (IllegalArgumentException ex) {
            // A malformed pattern (unbalanced brace, bad argument) is a CONTENT problem — the
            // port's never-throws contract holds by rendering the raw text instead.
            return text;
        }
    }

    private String lookup(String key, Locale locale) {
        if (locale != null) {
            String exact = locale.toLanguageTag().toLowerCase(Locale.ROOT);
            String found = bundles.bundle(exact).get(key);
            if (found != null) {
                return found;
            }
            String language = locale.getLanguage().toLowerCase(Locale.ROOT);
            if (!language.isEmpty() && !language.equals(exact)) {
                found = bundles.bundle(language).get(key);
                if (found != null) {
                    return found;
                }
            }
        }
        return bundles.bundle(properties.defaultLocale()).get(key);
    }
}
