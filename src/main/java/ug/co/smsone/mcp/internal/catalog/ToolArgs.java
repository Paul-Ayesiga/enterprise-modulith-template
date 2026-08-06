package ug.co.smsone.mcp.internal.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Argument access + JSON-Schema shorthand for manifests. Arguments arrive schema-validated by the
 * SDK (draft 2020-12), so these helpers only normalize types and turn a missing required value into
 * the SAME 422 shape REST validation produces — an agent reads one error vocabulary everywhere.
 */
final class ToolArgs {

    private ToolArgs() {
    }

    static String requireString(Map<String, Object> args, String name) {
        String value = optionalString(args, name);
        if (value == null || value.isBlank()) {
            throw new ValidationException("'" + name + "' is required.", ApiSource.pointer("/" + name));
        }
        return value.trim();
    }

    static String optionalString(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value == null ? null : String.valueOf(value);
    }

    static int intOr(Map<String, Object> args, String name, int fallback) {
        Object value = args.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new ValidationException("'" + name + "' must be an integer.", ApiSource.pointer("/" + name));
    }

    /** {@code page_size}/{@code page_after} → the house keyset page (defaults 20, cap 100). */
    static CursorPageRequest page(Map<String, Object> args) {
        return new CursorPageRequest(intOr(args, "page_size", CursorPageRequest.DEFAULT_SIZE),
                optionalString(args, "page_after"));
    }

    // --- JSON-Schema shorthand -------------------------------------------------------------

    static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) {
            schema.put("required", List.of(required));
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> integer(String description, int minimum, int maximum) {
        return Map.of("type", "integer", "description", description,
                "minimum", minimum, "maximum", maximum);
    }

    static Map<String, Object> pageProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("page_size", integer("Items per page (default 20).", 1, CursorPageRequest.MAX_SIZE));
        properties.put("page_after", string("Opaque cursor from the previous page's nextCursor."));
        return properties;
    }
}
