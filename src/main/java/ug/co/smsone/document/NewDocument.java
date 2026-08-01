package ug.co.smsone.document;

import java.util.UUID;

/**
 * A document to register over an already-stored object. {@code orgId} null = personal;
 * {@code ownerSubject} is the token subject of whoever the document belongs to;
 * {@code source} says where it came from ({@code UPLOAD} for the REST surface, {@code EXCHANGE}
 * for platform-produced artifacts).
 */
public record NewDocument(UUID orgId, String ownerSubject, String storageKey, String name,
        String contentType, long sizeBytes, String source) {

    public NewDocument {
        ownerSubject = require(ownerSubject, 64, "ownerSubject");
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
