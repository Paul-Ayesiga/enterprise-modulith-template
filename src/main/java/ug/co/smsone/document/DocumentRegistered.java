package ug.co.smsone.document;

import java.time.Instant;
import java.util.UUID;

/** A document joined the catalog — via upload or via a platform producer (source says which). */
public record DocumentRegistered(UUID documentId, UUID orgId, String name, String source,
        Instant occurredAt) {
}
