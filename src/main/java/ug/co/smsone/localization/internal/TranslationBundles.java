package ug.co.smsone.localization.internal;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cached per-locale bundle, as its own bean for the same reason {@code PermissionResolver}
 * exists: {@code MessagesImpl} walks several locales per resolve, and calling a {@code @Cacheable}
 * method on {@code this} would bypass the proxy — every fallback step would hit the database.
 */
@Component
class TranslationBundles {

    static final String BUNDLES_CACHE = "translation-bundles";

    private final TranslationRepository translations;

    TranslationBundles(TranslationRepository translations) {
        this.translations = translations;
    }

    @Cacheable(cacheNames = BUNDLES_CACHE, key = "#locale")
    @Transactional(readOnly = true)
    public Map<String, String> bundle(String locale) {
        return translations.findByLocale(locale).stream()
                .collect(Collectors.toUnmodifiableMap(Translation::getMsgKey, Translation::getMsgValue));
    }
}
