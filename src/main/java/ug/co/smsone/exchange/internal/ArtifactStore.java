package ug.co.smsone.exchange.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.document.Documents;
import ug.co.smsone.shared.document.NewDocument;
import ug.co.smsone.files.FileStorageProvider;

/**
 * The one place a job artifact (source, error report, export result) leaves the platform: bytes go
 * behind the files port, and the SAME act files it as an {@code EXCHANGE} document — so every
 * artifact is findable, owned, and lifecycle-managed like any other document.
 */
@Component
class ArtifactStore {

    /** Above this, uploads take the files port's multipart path (its javadoc's "a few MB"). */
    private static final long LARGE_UPLOAD_THRESHOLD = 5L * 1024 * 1024;

    private final FileStorageProvider storage;
    private final Documents documents;

    ArtifactStore(FileStorageProvider storage, Documents documents) {
        this.storage = storage;
        this.documents = documents;
    }

    String store(String key, InputStream content, long sizeBytes, String contentType, String name,
            UUID orgId, UUID ownerPersonId) {
        if (sizeBytes > LARGE_UPLOAD_THRESHOLD) {
            storage.putLarge(key, content, sizeBytes, contentType);
        } else {
            storage.put(key, content, sizeBytes, contentType);
        }
        documents.register(new NewDocument(orgId, ownerPersonId, key, name, contentType, sizeBytes, "EXCHANGE"));
        return key;
    }

    String store(Path file, String key, String contentType, String name, UUID orgId,
            UUID ownerPersonId) throws IOException {
        long size = Files.size(file);
        try (InputStream in = Files.newInputStream(file)) {
            return store(key, in, size, contentType, name, orgId, ownerPersonId);
        }
    }

    /**
     * {@code exch/o/<orgId>/<jobId>/<fileName>} — an organization's artifacts under its own prefix,
     * which is what makes them extractable (ADR 0010 §6 item 7) and what keeps two tenants from minting
     * the same key now that {@code uq_document_storage_key} exists once per schema.
     *
     * <p><b>A platform-scoped job has no key here, and that is deliberate rather than unhandled.</b>
     * {@code exchange_job.org_id} is nullable (V24:11 — null is a platform-scoped handler) and
     * {@code ExchangeWorker} already routes such a job to the platform axis, but no submit path creates
     * one today. If one ever does, {@code NewDocument} refuses the {@code exch/o/null/…} this would
     * produce — loudly, on the first artifact — rather than filing a personal document under a
     * namespace an extraction reads as an organization's. Give platform artifacts their own namespace
     * at that point; do not relax the guard.
     */
    static String artifactKey(UUID orgId, UUID jobId, String fileName) {
        return "exch/o/" + orgId + "/" + jobId + "/" + fileName;
    }

    static String safeFileName(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        return candidate.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
