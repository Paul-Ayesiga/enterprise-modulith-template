package ug.co.smsone.localization.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface TranslationRepository extends JpaRepository<Translation, UUID>, JpaSpecificationExecutor<Translation> {

    Optional<Translation> findByLocaleAndMsgKey(String locale, String msgKey);

    /** One whole locale — the bundle load behind each cache miss (idx_translation_locale). */
    List<Translation> findByLocale(String locale);
}
