package ug.co.smsone.identity.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Which of a {@link PersonName}'s seven components a caller asked to change, and what to change them
 * to. The parsed form of a {@code PATCH …/name} body.
 *
 * <p><b>The rule this type exists to make real: absent is not null.</b> A body that omits
 * {@code honorificSuffix} must leave it alone; a body that sends {@code "honorificSuffix": null} must
 * clear it. A record of plain Strings cannot tell those two apart — both arrive as a null component —
 * so a PUT-shaped payload silently erases every field a client did not know to send, which with seven
 * mostly-null columns is most of them. Hence a map: <b>key presence IS the instruction</b> and the
 * value is what to store. The same shape, for the same reason, as {@code PUT /api/v1/me/preferences}
 * ("only the sent keys change; a null value deletes its key").
 *
 * <p>The two alternatives were weighed and lost. A {@code JsonNullable}-style wrapper needs a custom
 * Jackson 3 deserializer (its {@code getNullValue} is the only hook that survives an explicit null),
 * which is machinery this codebase has nowhere else. An explicit {@code clear: [...]} list gives one
 * intent two grammars, and the first client that sends a field in both halves discovers which one wins
 * by experiment.
 *
 * <p>Unknown keys are <b>refused</b>, not ignored. That is the price of a map body: a typo'd
 * {@code familyname} that quietly did nothing looks exactly like a save that worked.
 *
 * <p>{@code formattedName} is changed only when the caller sends it, and is never composed from the
 * parts — see {@link PersonName} and V10 for why assembling one is wrong for much of the world.
 */
record NamePatch(Map<String, String> changes) {

    private static final String FORMATTED_NAME = "formattedName";
    private static final String GIVEN_NAME = "givenName";
    private static final String FAMILY_NAME = "familyName";
    private static final String MIDDLE_NAME = "middleName";
    private static final String HONORIFIC_PREFIX = "honorificPrefix";
    private static final String HONORIFIC_SUFFIX = "honorificSuffix";
    private static final String PREFERRED_NAME = "preferredName";

    /** Canonical order, so an audit rendering reads the same whatever order a client sent the keys in. */
    private static final List<String> FIELDS = List.of(FORMATTED_NAME, GIVEN_NAME, FAMILY_NAME,
            MIDDLE_NAME, HONORIFIC_PREFIX, HONORIFIC_SUFFIX, PREFERRED_NAME);

    /**
     * Mirrors {@code person}'s column widths (V10). Checking here is what makes an over-long name a 422
     * naming the field rather than a 500 at flush — and this map doubles as the wire-key allow-list, so
     * a field can only be accepted if it also has a width.
     */
    private static final Map<String, Integer> MAX_LENGTHS = Map.of(
            FORMATTED_NAME, 200,
            GIVEN_NAME, 100,
            FAMILY_NAME, 100,
            MIDDLE_NAME, 100,
            HONORIFIC_PREFIX, 50,
            HONORIFIC_SUFFIX, 50,
            PREFERRED_NAME, 100);

    NamePatch {
        // Map.copyOf would throw here: it rejects null VALUES, and a null value is precisely this
        // type's "clear it" instruction. An unmodifiable LinkedHashMap keeps them.
        changes = Collections.unmodifiableMap(new LinkedHashMap<>(changes));
    }

    /**
     * Parses and validates a request body. Every value is trimmed, and a blank one becomes null — so a
     * client sending {@code ""} clears the field instead of storing whitespace, exactly as an explicit
     * null does.
     */
    static NamePatch of(Map<String, String> body) {
        Map<String, String> changes = new LinkedHashMap<>();
        if (body != null) {
            body.forEach((field, value) -> changes.put(requireKnown(field), normalize(field, value)));
        }
        return new NamePatch(changes);
    }

    /**
     * {@code current} with the sent components replaced; everything else carried through untouched.
     * An empty patch therefore returns an equal name — a body of {@code {}} is a legitimate no-op, not
     * an error, and {@link PersonNameService} turns that equality into "nothing to write".
     */
    PersonName applyTo(PersonName current) {
        return new PersonName(
                patched(FORMATTED_NAME, current),
                patched(GIVEN_NAME, current),
                patched(FAMILY_NAME, current),
                patched(MIDDLE_NAME, current),
                patched(HONORIFIC_PREFIX, current),
                patched(HONORIFIC_SUFFIX, current),
                patched(PREFERRED_NAME, current));
    }

    /**
     * The sent components as they stand in {@code state} — one half of an audit row's from/to pair.
     *
     * <p>Only the sent components, so the trail reads as "these are the fields this request touched"
     * rather than dumping the whole name twice. It also keeps the rendering inside
     * {@code audit_log.from_state}'s varchar(1000): the seven widths sum to 700 characters, and the
     * labels and quotes add under 110 more.
     */
    String render(PersonName state) {
        StringBuilder rendered = new StringBuilder();
        for (String field : FIELDS) {
            if (!changes.containsKey(field)) {
                continue;
            }
            if (!rendered.isEmpty()) {
                rendered.append(' ');
            }
            String value = valueOf(state, field);
            rendered.append(field).append('=').append(value == null ? "null" : "'" + value + "'");
        }
        return rendered.toString();
    }

    private String patched(String field, PersonName current) {
        return changes.containsKey(field) ? changes.get(field) : valueOf(current, field);
    }

    private static String requireKnown(String field) {
        if (!MAX_LENGTHS.containsKey(field)) {
            throw new ValidationException("'" + shortened(field) + "' is not part of a name. The name is "
                    + "formattedName, givenName, familyName, middleName, honorificPrefix, "
                    + "honorificSuffix and preferredName.", pointerTo(field));
        }
        return field;
    }

    private static String normalize(String field, String value) {
        if (value == null) {
            return null; // an explicit null clears — the whole point of the map
        }
        // Trim BEFORE measuring: whitespace is not a name, so a blank clears rather than storing
        // spaces, and a value padded past its column by trailing blanks is not a 422.
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int max = MAX_LENGTHS.get(field);
        // String.length() counts UTF-16 units where varchar counts code points, so this is
        // conservative by construction: it can refuse a little early for astral-plane characters
        // (emoji, some historic scripts), and can never let through something the column would reject.
        if (trimmed.length() > max) {
            throw new ValidationException(
                    "A " + field + " may be at most " + max + " characters.", pointerTo(field));
        }
        return trimmed;
    }

    private static String valueOf(PersonName name, String field) {
        return switch (field) {
            case FORMATTED_NAME -> name.formattedName();
            case GIVEN_NAME -> name.givenName();
            case FAMILY_NAME -> name.familyName();
            case MIDDLE_NAME -> name.middleName();
            case HONORIFIC_PREFIX -> name.honorificPrefix();
            case HONORIFIC_SUFFIX -> name.honorificSuffix();
            case PREFERRED_NAME -> name.preferredName();
            // Unreachable: nothing enters `changes` without passing requireKnown. It fails fast so
            // that adding a component to MAX_LENGTHS and forgetting it here is a loud programmer
            // error rather than a field that silently refuses to be edited.
            default -> throw new IllegalStateException("Unmapped name component: " + field);
        };
    }

    /**
     * The offending key, echoed back so a client can find it — truncated because it is caller input,
     * and a 10 kB key must not buy a 10 kB error.
     */
    private static ApiSource pointerTo(String field) {
        return ApiSource.pointer("/data/attributes/" + shortened(field));
    }

    private static String shortened(String field) {
        if (field == null) {
            return "";
        }
        return field.length() <= 60 ? field : field.substring(0, 60);
    }
}
