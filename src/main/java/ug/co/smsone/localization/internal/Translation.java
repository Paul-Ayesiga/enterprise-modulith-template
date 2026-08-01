package ug.co.smsone.localization.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.localization.TranslationChanged;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** One translated message: a (locale, key) → value row. Locales are lowercased BCP-47 tags. */
@Entity
@Table(name = "translation")
@SQLDelete(sql = "update translation set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class Translation extends SoftDeletableEntity {

    @Column(nullable = false, updatable = false, length = 35)
    private String locale;

    @Column(name = "msg_key", nullable = false, updatable = false, length = 200)
    private String msgKey;

    @Column(name = "msg_value", nullable = false, columnDefinition = "text")
    private String msgValue;

    protected Translation() {
        // JPA
    }

    static Translation of(String locale, String key, String value) {
        Translation translation = new Translation();
        translation.locale = locale;
        translation.msgKey = key;
        translation.change(value);
        return translation;
    }

    void change(String value) {
        if (Objects.equals(value, this.msgValue)) {
            return; // idempotent no-op: no spurious event, no x → x audit row
        }
        this.msgValue = value;
        registerEvent(new TranslationChanged(locale, msgKey, Instant.now()));
    }

    String getLocale() {
        return locale;
    }

    String getMsgKey() {
        return msgKey;
    }

    String getMsgValue() {
        return msgValue;
    }
}
