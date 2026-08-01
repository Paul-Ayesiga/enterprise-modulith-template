package ug.co.smsone.localization.internal;

import java.time.Instant;
import java.util.Locale;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.localization.TranslationChanged;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/** Translation CRUD. Writers evict the locale's cached bundle (and broadcast — ADR 0004). */
@Service
@Transactional
class TranslationService {

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final TranslationRepository translations;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;

    TranslationService(TranslationRepository translations, AuditLog auditLog,
            ApplicationEventPublisher events) {
        this.translations = translations;
        this.auditLog = auditLog;
        this.events = events;
    }

    @Transactional(readOnly = true)
    Window<Translation> list(String locale, CursorPageRequest page) {
        String normalized = locale == null ? null : normalizeLocale(locale);
        return translations.findBy(
                (root, query, cb) -> normalized == null
                        ? cb.conjunction()
                        : cb.equal(root.get("locale"), normalized),
                q -> q.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition(LIST_SORT)));
    }

    @Transactional(readOnly = true)
    Translation require(String locale, String key) {
        return translations.findByLocaleAndMsgKey(normalizeLocale(locale), key)
                .orElseThrow(() -> new NotFoundException("Translation not found."));
    }

    // The evict key repeats normalizeLocale's trim+lowercase in SpEL (rather than #result.locale)
    // because the entity's getters are package-private, which SpEL cannot read.
    @CacheEvict(cacheNames = TranslationBundles.BUNDLES_CACHE, key = "#locale.trim().toLowerCase()")
    Translation put(String locale, String key, String value) {
        String normalized = normalizeLocale(locale);
        Translation translation = translations.findByLocaleAndMsgKey(normalized, key)
                .map(existing -> {
                    String previous = existing.getMsgValue();
                    existing.change(value);
                    if (!value.equals(previous)) {
                        auditLog.record("localization.translation_changed", null,
                                normalized + ":" + key, previous, value);
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    Translation created = Translation.of(normalized, key, value);
                    auditLog.record("localization.translation_changed", null,
                            normalized + ":" + key, null, value);
                    return created;
                });
        return translations.save(translation);
    }

    @CacheEvict(cacheNames = TranslationBundles.BUNDLES_CACHE, key = "#locale.trim().toLowerCase()")
    void delete(String locale, String key) {
        Translation translation = require(locale, key);
        translations.delete(translation); // soft: @SQLDelete stamps deleted_at
        // A delete fires no @DomainEvents — publish explicitly, like MemberService.remove.
        events.publishEvent(new TranslationChanged(translation.getLocale(), key, Instant.now()));
        auditLog.record("localization.translation_deleted", null,
                translation.getLocale() + ":" + key, translation.getMsgValue(), null);
    }

    /**
     * Lowercased BCP-47, validated by round-trip: tags are case-insensitive per RFC 5646, so one
     * canonical casing keeps (locale, key) unique in the table AND the cache.
     */
    private static String normalizeLocale(String locale) {
        String normalized = locale.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || Locale.forLanguageTag(normalized).getLanguage().isEmpty()) {
            throw new ValidationException("'" + locale + "' is not a valid BCP-47 language tag.",
                    ApiSource.parameter("locale"));
        }
        return normalized;
    }
}
