package ug.co.smsone.localization;

import java.time.Instant;

/** A translation was created, replaced or deleted (deletes publish explicitly). */
public record TranslationChanged(String locale, String key, Instant occurredAt) {
}
