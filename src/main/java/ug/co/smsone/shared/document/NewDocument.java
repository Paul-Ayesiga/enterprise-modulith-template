package ug.co.smsone.shared.document;

import java.util.UUID;

/**
 * A document to register over an already-stored object. {@code orgId} null = personal;
 * {@code ownerPersonId} is the {@code person.id} of whoever the document belongs to;
 * {@code source} says where it came from ({@code UPLOAD} for the REST surface, {@code EXCHANGE}
 * for platform-produced artifacts).
 *
 * <p>The owner is required and is never null: {@code document.owner_person_id} is NOT NULL (V23), and a
 * document belongs to a human even when a job produced it. A machine caller therefore cannot register
 * one — that refusal belongs at the door rather than three layers down as a constraint violation.
 */
public record NewDocument(UUID orgId, UUID ownerPersonId, String storageKey, String name,
        String contentType, long sizeBytes, String source) {

    public NewDocument {
        if (ownerPersonId == null) {
            throw new IllegalArgumentException("NewDocument.ownerPersonId must not be null");
        }
        storageKey = require(storageKey, 300, "storageKey");
        name = require(name, 255, "name");
        contentType = require(contentType, 100, "contentType");
        source = require(source, 20, "source");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("NewDocument.sizeBytes must not be negative");
        }
    }

    private static String require(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("NewDocument." + field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("NewDocument." + field + " exceeds " + max + " characters");
        }
        return trimmed;
    }
}
