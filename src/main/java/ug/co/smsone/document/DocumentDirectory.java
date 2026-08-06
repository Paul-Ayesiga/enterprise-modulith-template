package ug.co.smsone.document;

import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Org-document port for other protocol surfaces (the MCP module today): metadata reads, short-lived
 * download URLs, delete. Deliberately NO registration — putting bytes into the platform is a
 * multipart REST upload, and bulk data never transits MCP (plan §8); agents fetch via the presigned
 * URL instead.
 */
public interface DocumentDirectory {

    WindowedResult<DocumentView> list(UUID orgId, CursorPageRequest page);

    DocumentView get(UUID orgId, UUID documentId);

    /** A short-lived presigned GET for the document's bytes — the object is checked to exist. */
    String downloadUrl(UUID orgId, UUID documentId);

    /** Bytes now, row soft — the same asymmetry the REST delete has. */
    void delete(UUID orgId, UUID documentId);

    record DocumentView(UUID id, String name, String contentType, long sizeBytes, String source,
            Instant createdAt) {
    }
}
