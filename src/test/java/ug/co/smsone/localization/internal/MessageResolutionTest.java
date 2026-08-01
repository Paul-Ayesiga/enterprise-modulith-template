package ug.co.smsone.localization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ug.co.smsone.shared.i18n.Messages;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The port's contract IS the fallback chain — each step asserted individually, because a chain
 * that skips a step still answers something and would otherwise pass on vibes.
 */
class MessageResolutionTest extends AbstractIntegrationTest {

    @Autowired
    private TranslationService service;

    @Autowired
    private Messages messages;

    @Test
    void fallsBackExactThenLanguageThenDefaultThenKey() {
        String key = "probe.greeting." + UUID.randomUUID();
        service.put("en", key, "Hello");
        service.put("de", key, "Hallo");
        service.put("de-CH", key, "Grüezi");

        assertThat(messages.resolve(key, Locale.forLanguageTag("de-CH"))).isEqualTo("Grüezi");
        assertThat(messages.resolve(key, Locale.forLanguageTag("de-AT"))).isEqualTo("Hallo");
        assertThat(messages.resolve(key, Locale.forLanguageTag("fr"))).isEqualTo("Hello");
        assertThat(messages.resolve(key, null)).isEqualTo("Hello");

        String missing = "probe.missing." + UUID.randomUUID();
        assertThat(messages.resolve(missing, Locale.GERMAN))
                .as("a catalog gap renders the key, it never throws")
                .isEqualTo(missing);
    }

    @Test
    void argumentsAreFormattedIntoTheResolvedText() {
        String key = "probe.args." + UUID.randomUUID();
        service.put("en", key, "Hello {0}, you have {1} items");

        assertThat(messages.resolve(key, Locale.ENGLISH, "Paul", 3))
                .isEqualTo("Hello Paul, you have 3 items");
    }

    @Test
    void writesAndDeletesEvictTheCachedBundle() {
        String key = "probe.evict." + UUID.randomUUID();
        service.put("en", key, "old");
        assertThat(messages.resolve(key, Locale.ENGLISH)).isEqualTo("old"); // bundle now cached

        service.put("en", key, "new");
        assertThat(messages.resolve(key, Locale.ENGLISH))
                .as("a write must evict the cached bundle").isEqualTo("new");

        service.delete("en", key);
        assertThat(messages.resolve(key, Locale.ENGLISH))
                .as("after delete, resolution falls through to the key").isEqualTo(key);
    }
}
