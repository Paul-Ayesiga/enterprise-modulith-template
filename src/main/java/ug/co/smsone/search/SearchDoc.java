package ug.co.smsone.search;

import java.util.UUID;

/**
 * One searchable document. {@code orgId} is the tenant key — null means platform-wide, reachable
 * only through the admin search. {@code entityType}/{@code entityId} are the producer's vocabulary
 * and its dedup key; title and body are what the tsvector is generated from.
 */
public record SearchDoc(UUID orgId, String entityType, String entityId, String title, String body) {

    public SearchDoc {
        entityType = requireSized(entityType, 40, "entityType");
        entityId = requireSized(entityId, 64, "entityId");
        title = requireSized(title, 300, "title");
        body = body == null ? "" : body;
    }

    private static String requireSized(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SearchDoc." + field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("SearchDoc." + field + " exceeds " + max + " characters");
        }
        return trimmed;
    }
}
