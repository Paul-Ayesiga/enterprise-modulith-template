package ug.co.smsone.files;

import java.io.IOException;
import java.io.InputStream;

/**
 * One stored object in transit: its key, its bytes, and the two pieces of metadata a faithful copy has
 * to carry with them.
 *
 * <p><b>Why the content type travels rather than being re-guessed.</b> Downloads in this codebase are a
 * 302 to a presigned URL, so the object's OWN stored content type is what the browser sees — nothing on
 * the application side re-states it (`FileController.download`, `DocumentService.downloadUrl`). An
 * extraction that copied bytes alone would rebuild an organization's document store with every object
 * typed {@code application/octet-stream}, and the symptom on the far side is that every PDF downloads
 * as a file instead of opening. That is silent, it is not recoverable from the bytes, and it is the
 * whole reason this record exists instead of an {@code InputStream}.
 *
 * <p><b>The size is the S3 contract, not a convenience.</b> {@code PutObjectRequest} needs the content
 * length up front — {@code RequestBody.fromInputStream} takes it as an argument — so a copy that did
 * not carry the size would have to buffer the object in heap to find it out, and an extraction is
 * uncapped by definition.
 *
 * <p><b>Ownership: whoever opened it closes it.</b> The stream is live and holds a connection. When
 * {@code FileStorageProvider.open} produced it, closing it is the caller's job; when a bundle reader
 * produced one to hand to {@code write}, closing it is the reader's. {@link #close()} deliberately
 * declares no checked exception so a try-with-resources needs no catch block, and a close failure is
 * wrapped rather than swallowed — a truncated read that only shows up as a short file later is exactly
 * the failure this class is trying to make impossible.
 */
public record StoredObject(String key, String contentType, long sizeBytes, InputStream content)
        implements AutoCloseable {

    public StoredObject {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("StoredObject.key must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("StoredObject.content must not be null — an object with no "
                    + "stream cannot be copied, and a null here would surface as an NPE inside the SDK");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("StoredObject.sizeBytes must not be negative");
        }
        // The store's own default when it has nothing better, kept here so no caller has to repeat it.
        contentType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    @Override
    public void close() {
        try {
            content.close();
        } catch (IOException failure) {
            throw new FileStorageException("could not close the stream for object " + key, failure);
        }
    }
}
