package ug.co.smsone.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

/**
 * The codec must be injective: a String key value containing the codec's own structural characters
 * ({@code |}, {@code =}) previously made the server mint a {@code links.next} its own decode
 * rejected as a client-blamed 422 — latent until the first name-sorted collection.
 */
class CursorsEscapingTest {

    @Test
    void aStringValueContainingTheStructuralCharactersRoundTrips() {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("name", "s:a|b=c%d|%7C");
        keys.put("createdAt", Instant.parse("2026-08-01T00:00:00Z"));
        keys.put("id", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        KeysetScrollPosition minted = ScrollPosition.forward(keys);

        KeysetScrollPosition decoded = Cursors.decode(Cursors.encode(minted));

        assertThat(decoded.getKeys()).isEqualTo(minted.getKeys());
    }
}
