package ug.co.smsone.shared.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import ug.co.smsone.shared.error.ValidationException;

/**
 * Opaque keyset-cursor codec: {@code property=<type>:<value>} pairs joined with {@code |},
 * base64url-encoded. Supported value types: Instant (t), UUID (u), Long (l), String (s) —
 * enough for keysets built from audited entities (createdAt, id, names). String values are
 * percent-escaped for the two structural characters, so a value containing {@code |} or {@code =}
 * cannot make the server mint a cursor its own decode rejects.
 */
public final class Cursors {

    private static final String PAIR_SEPARATOR = "|";
    private static final String INVALID_CURSOR_DETAIL = "page[after] is not a valid cursor.";

    private Cursors() {
    }

    public static String encode(KeysetScrollPosition position) {
        StringBuilder plain = new StringBuilder();
        for (Map.Entry<String, Object> entry : position.getKeys().entrySet()) {
            if (!plain.isEmpty()) {
                plain.append(PAIR_SEPARATOR);
            }
            plain.append(entry.getKey()).append('=').append(tag(entry.getValue()));
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static KeysetScrollPosition decode(String cursor) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Map<String, Object> keys = new LinkedHashMap<>();
            for (String pair : plain.split("\\" + PAIR_SEPARATOR)) {
                int eq = pair.indexOf('=');
                String property = pair.substring(0, eq);
                String tagged = pair.substring(eq + 1);
                keys.put(property, untag(tagged));
            }
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("empty cursor");
            }
            return ScrollPosition.forward(keys);
        } catch (RuntimeException e) {
            throw new ValidationException(INVALID_CURSOR_DETAIL, ApiSource.parameter("page[after]"));
        }
    }

    private static String tag(Object value) {
        return switch (value) {
            case Instant instant -> "t:" + instant;
            case UUID uuid -> "u:" + uuid;
            case Long number -> "l:" + number;
            case Double number -> "d:" + number; // search relevance ranks — Double.toString round-trips exactly
            case String string -> "s:" + escape(string);
            default -> throw new IllegalArgumentException(
                    "Unsupported cursor key type: " + value.getClass().getName());
        };
    }

    private static Object untag(String tagged) {
        String value = tagged.substring(2);
        return switch (tagged.charAt(0)) {
            case 't' -> Instant.parse(value);
            case 'u' -> UUID.fromString(value);
            case 'l' -> Long.parseLong(value);
            case 'd' -> Double.parseDouble(value);
            case 's' -> unescape(value);
            default -> throw new IllegalArgumentException("Unknown cursor type tag");
        };
    }

    // Only String values need it — the other types cannot contain the structural characters.
    // '%' first on escape and last on unescape, so escapes never re-escape themselves.
    private static String escape(String value) {
        return value.replace("%", "%25").replace("|", "%7C").replace("=", "%3D");
    }

    private static String unescape(String value) {
        return value.replace("%7C", "|").replace("%3D", "=").replace("%25", "%");
    }
}
